package com.studentlearning.platform2026.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSearchResult
import com.studentlearning.platform2026.domain.pdf.ArabicTextNormalizer
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

interface PdfPageBitmapRenderer {
    suspend fun renderPage(pageIndexZeroBased: Int, targetWidthPx: Int): Bitmap?
    fun evictCaches()
}

interface PdfDocumentSessionFactory {
    suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession
}

private object PdfBoxInitializer {
    private val initialized = AtomicBoolean(false)

    fun ensureInitialized(appContext: Context) {
        if (!initialized.get()) {
            synchronized(this) {
                if (!initialized.get()) {
                    PDFBoxResourceLoader.init(appContext)
                    initialized.set(true)
                }
            }
        }
    }
}

class AndroidPdfDocumentSessionFactory(
    context: Context,
    private val dispatcherProvider: DispatcherProvider
) : PdfDocumentSessionFactory {

    private val appContext = context.applicationContext

    override suspend fun openSession(
        fileId: String,
        preparedFile: PreparedPdfFile
    ): PdfDocumentSession = withContext(dispatcherProvider.io) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(preparedFile.file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) {
                throw PdfLoadException(
                    reason = PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE,
                    message = "PDF document contains zero readable pages."
                )
            }
            AndroidPdfDocumentSession(
                appContext = appContext,
                fileId = fileId,
                preparedFile = preparedFile,
                parcelFileDescriptor = pfd,
                pdfRenderer = renderer,
                dispatcherProvider = dispatcherProvider
            )
        } catch (cancelled: CancellationException) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            if (preparedFile.deleteOnClose) {
                preparedFile.file.delete()
            }
            throw cancelled
        } catch (pdfError: PdfLoadException) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            if (preparedFile.deleteOnClose) {
                preparedFile.file.delete()
            }
            throw pdfError
        } catch (security: SecurityException) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            if (preparedFile.deleteOnClose) {
                preparedFile.file.delete()
            }
            throw PdfLoadException(
                reason = PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE,
                message = "Encrypted or password-protected PDF files are not supported.",
                cause = security
            )
        } catch (io: IOException) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            if (preparedFile.deleteOnClose) {
                preparedFile.file.delete()
            }
            throw PdfLoadException(
                reason = PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE,
                message = io.localizedMessage ?: "Failed to decode PDF file.",
                cause = io
            )
        } catch (unexpected: Throwable) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            if (preparedFile.deleteOnClose) {
                preparedFile.file.delete()
            }
            throw PdfLoadException(
                reason = PdfFailureReason.UNKNOWN,
                message = unexpected.localizedMessage ?: "Unexpected error opening PDF.",
                cause = unexpected
            )
        }
    }
}

/**
 * Native Android [PdfRenderer] + lazy [PDFTextStripper] session bounded to a tiny LRU bitmap window.
 *
 * - Never decodes all pages at once; pages are rendered on-demand when visible.
 * - Opens a single [PdfRenderer.Page] at a time under [renderMutex].
 * - Lazily parses text with PDFBox only when [searchText] is requested, page by page.
 * - Releases native descriptors and ephemeral temp files cleanly in [close].
 */
class AndroidPdfDocumentSession(
    private val appContext: Context,
    override val fileId: String,
    private val preparedFile: PreparedPdfFile,
    private val parcelFileDescriptor: ParcelFileDescriptor,
    private val pdfRenderer: PdfRenderer,
    private val dispatcherProvider: DispatcherProvider
) : PdfDocumentSession, PdfPageBitmapRenderer {

    private data class PageCacheKey(
        val pageIndex: Int,
        val bucketWidthPx: Int
    )

    private val closed = AtomicBoolean(false)
    private val renderMutex = Mutex()
    private val searchMutex = Mutex()
    private val aspectRatioCache = mutableMapOf<Int, Float>()

    // Bounded window of 4 rendered pages avoids high memory usage on large PDFs.
    // We intentionally avoid aggressive bitmap.recycle() here so Compose frames never crash
    // trying to draw an evicted bitmap that just left the cache window.
    private val pageBitmapCache = object : LruCache<PageCacheKey, Bitmap>(MAX_CACHED_PAGES) {}

    override val pageCount: Int = pdfRenderer.pageCount

    override suspend fun getPageAspectRatio(pageIndexZeroBased: Int): Float {
        if (closed.get() || pageIndexZeroBased !in 0 until pageCount) {
            return DEFAULT_PAGE_ASPECT_RATIO
        }
        return withContext(dispatcherProvider.io) {
            renderMutex.withLock {
                if (closed.get()) return@withLock DEFAULT_PAGE_ASPECT_RATIO
                aspectRatioCache[pageIndexZeroBased]?.let { return@withLock it }

                val page = pdfRenderer.openPage(pageIndexZeroBased)
                try {
                    val ratio = if (page.height > 0) {
                        (page.width.toFloat() / page.height.toFloat()).coerceIn(0.4f, 2.5f)
                    } else {
                        DEFAULT_PAGE_ASPECT_RATIO
                    }
                    aspectRatioCache[pageIndexZeroBased] = ratio
                    ratio
                } finally {
                    page.close()
                }
            }
        }
    }

    override suspend fun renderPage(pageIndexZeroBased: Int, targetWidthPx: Int): Bitmap? {
        if (closed.get() || pageIndexZeroBased !in 0 until pageCount) return null

        val clampedWidth = targetWidthPx.coerceIn(MIN_RENDER_WIDTH_PX, MAX_RENDER_WIDTH_PX)
        val bucketWidth = ((clampedWidth + WIDTH_BUCKET_STEP_PX - 1) / WIDTH_BUCKET_STEP_PX) * WIDTH_BUCKET_STEP_PX
        val cacheKey = PageCacheKey(pageIndexZeroBased, bucketWidth)
        pageBitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        return withContext(dispatcherProvider.io) {
            renderMutex.withLock {
                if (closed.get()) return@withLock null
                pageBitmapCache.get(cacheKey)?.let { cached ->
                    if (!cached.isRecycled) return@withLock cached
                }

                val page = pdfRenderer.openPage(pageIndexZeroBased)
                try {
                    val aspect = if (page.height > 0 && page.width > 0) {
                        page.width.toFloat() / page.height.toFloat()
                    } else {
                        DEFAULT_PAGE_ASPECT_RATIO
                    }
                    aspectRatioCache[pageIndexZeroBased] = aspect.coerceIn(0.4f, 2.5f)

                    val rawHeight = (bucketWidth / aspect).toInt().coerceAtLeast(1)
                    val scaleFactor = if (rawHeight > MAX_RENDER_HEIGHT_PX) {
                        MAX_RENDER_HEIGHT_PX.toFloat() / rawHeight.toFloat()
                    } else {
                        1f
                    }
                    val finalWidth = (bucketWidth * scaleFactor).toInt().coerceAtLeast(1)
                    val finalHeight = (rawHeight * scaleFactor).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (!closed.get()) {
                        pageBitmapCache.put(cacheKey, bitmap)
                    }
                    bitmap
                } finally {
                    page.close()
                }
            }
        }
    }

    override suspend fun searchText(query: String): PdfSearchResult {
        if (closed.get()) {
            return PdfSearchResult(query = query.trim(), status = com.studentlearning.platform2026.domain.model.PdfSearchStatus.ERROR)
        }
        return withContext(dispatcherProvider.io) {
            searchMutex.withLock {
                if (closed.get()) {
                    return@withLock PdfSearchResult(
                        query = query.trim(),
                        status = com.studentlearning.platform2026.domain.model.PdfSearchStatus.ERROR
                    )
                }

                PdfBoxInitializer.ensureInitialized(appContext)
                var document: PDDocument? = null
                try {
                    val coroutineContext = currentCoroutineContext()
                    document = PDDocument.load(preparedFile.file)
                    val docPageCount = document.numberOfPages.coerceAtLeast(pageCount)
                    val stripper = PDFTextStripper()

                    ArabicTextNormalizer.searchAcrossPages(
                        query = query,
                        pageCount = docPageCount,
                        extractPageText = { pageOneBased ->
                            if (pageOneBased > document.numberOfPages) {
                                ""
                            } else {
                                stripper.startPage = pageOneBased
                                stripper.endPage = pageOneBased
                                stripper.getText(document) ?: ""
                            }
                        },
                        onCheckCancelled = { coroutineContext.ensureActive() }
                    )
                } finally {
                    runCatching { document?.close() }
                }
            }
        }
    }

    override fun evictCaches() {
        pageBitmapCache.evictAll()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        evictCaches()
        aspectRatioCache.clear()
        runCatching { pdfRenderer.close() }
        runCatching { parcelFileDescriptor.close() }
        if (preparedFile.deleteOnClose) {
            runCatching { preparedFile.file.delete() }
        }
    }

    private companion object {
        const val MAX_CACHED_PAGES = 4
        const val DEFAULT_PAGE_ASPECT_RATIO = 0.707f // Standard A4 portrait (~1:1.414)
        const val MIN_RENDER_WIDTH_PX = 480
        const val MAX_RENDER_WIDTH_PX = 2048
        const val MAX_RENDER_HEIGHT_PX = 3072
        const val WIDTH_BUCKET_STEP_PX = 256
    }
}
