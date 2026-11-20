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
import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import com.studentlearning.platform2026.domain.pdf.ArabicTextNormalizer
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

interface PdfPageBitmapRenderer {
    suspend fun renderPage(pageIndexZeroBased: Int, targetWidthPx: Int): Bitmap?
    fun evictCaches()
}

interface PdfDocumentSessionFactory {
    suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession
}

/**
 * Pure Kotlin dimension & pixel-budget helpers for safe [Bitmap] allocation across devices,
 * keeping per-page decoded bitmaps bounded even during high zoom levels.
 */
object PdfRenderSizing {
    const val DEFAULT_PAGE_ASPECT_RATIO = 0.707f // Standard A4 portrait (~1:1.414)
    const val MIN_RENDER_WIDTH_PX = 480
    const val MAX_RENDER_WIDTH_PX = 2048
    const val MAX_RENDER_HEIGHT_PX = 3072
    const val WIDTH_BUCKET_STEP_PX = 256
    const val MAX_BITMAP_PIXELS: Long = 2048L * 2048L // ~16 MiB in ARGB_8888
    const val MAX_BITMAP_CACHE_KB: Int = 24 * 1024 // 24 MiB LRU budget

    data class RenderDimensions(
        val widthPx: Int,
        val heightPx: Int
    ) {
        val estimatedBytes: Long
            get() = widthPx.toLong() * heightPx.toLong() * 4L
    }

    fun quantizeWidthPx(targetWidthPx: Int): Int {
        val clamped = targetWidthPx.coerceIn(MIN_RENDER_WIDTH_PX, MAX_RENDER_WIDTH_PX)
        val bucketed = ((clamped + WIDTH_BUCKET_STEP_PX - 1) / WIDTH_BUCKET_STEP_PX) * WIDTH_BUCKET_STEP_PX
        return bucketed.coerceIn(MIN_RENDER_WIDTH_PX, MAX_RENDER_WIDTH_PX)
    }

    fun computeSafeDimensions(
        targetWidthPx: Int,
        pageWidth: Int,
        pageHeight: Int,
        scaleFactorOverride: Float = 1.0f
    ): RenderDimensions {
        val bucketWidth = quantizeWidthPx(targetWidthPx)
        val aspect = if (pageWidth > 0 && pageHeight > 0) {
            (pageWidth.toFloat() / pageHeight.toFloat()).coerceIn(0.25f, 4.0f)
        } else {
            DEFAULT_PAGE_ASPECT_RATIO
        }

        val desiredWidth = (bucketWidth * scaleFactorOverride).coerceAtLeast(1f)
        val desiredHeight = (desiredWidth / aspect).coerceAtLeast(1f)

        val heightCapScale = if (desiredHeight > MAX_RENDER_HEIGHT_PX) {
            MAX_RENDER_HEIGHT_PX.toFloat() / desiredHeight
        } else {
            1f
        }
        val cappedWidth = desiredWidth * heightCapScale
        val cappedHeight = desiredHeight * heightCapScale

        val pixelCount = cappedWidth.toDouble() * cappedHeight.toDouble()
        val pixelCapScale = if (pixelCount > MAX_BITMAP_PIXELS.toDouble()) {
            sqrt(MAX_BITMAP_PIXELS.toDouble() / pixelCount).toFloat()
        } else {
            1f
        }

        val finalWidth = (cappedWidth * pixelCapScale).toInt().coerceAtLeast(1)
        val finalHeight = (cappedHeight * pixelCapScale).toInt().coerceAtLeast(1)
        return RenderDimensions(widthPx = finalWidth, heightPx = finalHeight)
    }
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
 * Native Android [PdfRenderer] + on-demand PDFBox [PDFTextStripper] session backed by a byte-budgeted
 * LRU bitmap cache.
 *
 * - Never decodes all pages to bitmaps at once; pages are rendered on-demand when visible.
 * - Opens a single [PdfRenderer.Page] at a time under [renderMutex].
 * - Coordinates [close] with in-flight operations via [activeOperations] so native handles and
 *   temporary files are never closed/deleted out from under active IO work.
 * - During [searchText], PDFBox still parses the document object/page tree in memory when loading
 *   `PDDocument`, using [MemoryUsageSetting.setupMixed] to spill stream buffers above 4 MiB to an
 *   isolated scratch folder cleaned up in `finally`.
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
    private val resourcesReleased = AtomicBoolean(false)
    private val activeOperations = AtomicInteger(0)
    private val renderMutex = Mutex()
    private val searchMutex = Mutex()
    private val aspectRatioCache = mutableMapOf<Int, Float>()

    // Byte-budgeted LRU cache (24 MiB) avoids unbounded heap growth on multi-page zoom renders.
    // We intentionally avoid aggressive bitmap.recycle() here so Compose frames never crash
    // trying to draw an evicted bitmap that just left the cache window.
    private val pageBitmapCache = object : LruCache<PageCacheKey, Bitmap>(
        PdfRenderSizing.MAX_BITMAP_CACHE_KB
    ) {
        override fun sizeOf(key: PageCacheKey, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    override val pageCount: Int = pdfRenderer.pageCount

    override suspend fun getPageAspectRatio(pageIndexZeroBased: Int): Float {
        if (pageIndexZeroBased !in 0 until pageCount) {
            return PdfRenderSizing.DEFAULT_PAGE_ASPECT_RATIO
        }
        if (!tryAcquireOperation()) {
            return PdfRenderSizing.DEFAULT_PAGE_ASPECT_RATIO
        }
        return try {
            withContext(dispatcherProvider.io) {
                renderMutex.withLock {
                    if (closed.get()) return@withLock PdfRenderSizing.DEFAULT_PAGE_ASPECT_RATIO
                    aspectRatioCache[pageIndexZeroBased]?.let { return@withLock it }

                    val page = pdfRenderer.openPage(pageIndexZeroBased)
                    try {
                        val ratio = if (page.height > 0 && page.width > 0) {
                            (page.width.toFloat() / page.height.toFloat()).coerceIn(0.4f, 2.5f)
                        } else {
                            PdfRenderSizing.DEFAULT_PAGE_ASPECT_RATIO
                        }
                        aspectRatioCache[pageIndexZeroBased] = ratio
                        ratio
                    } finally {
                        page.close()
                    }
                }
            }
        } finally {
            releaseOperation()
        }
    }

    override suspend fun renderPage(pageIndexZeroBased: Int, targetWidthPx: Int): Bitmap? {
        if (pageIndexZeroBased !in 0 until pageCount) return null
        if (!tryAcquireOperation()) return null

        return try {
            val bucketWidth = PdfRenderSizing.quantizeWidthPx(targetWidthPx)
            val cacheKey = PageCacheKey(pageIndexZeroBased, bucketWidth)
            pageBitmapCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) return cached
            }

            withContext(dispatcherProvider.io) {
                renderMutex.withLock {
                    if (closed.get()) return@withLock null
                    pageBitmapCache.get(cacheKey)?.let { cached ->
                        if (!cached.isRecycled) return@withLock cached
                    }

                    val page = pdfRenderer.openPage(pageIndexZeroBased)
                    try {
                        val aspect = if (page.height > 0 && page.width > 0) {
                            (page.width.toFloat() / page.height.toFloat()).coerceIn(0.25f, 4.0f)
                        } else {
                            PdfRenderSizing.DEFAULT_PAGE_ASPECT_RATIO
                        }
                        aspectRatioCache[pageIndexZeroBased] = aspect.coerceIn(0.4f, 2.5f)

                        val primaryDims = PdfRenderSizing.computeSafeDimensions(
                            targetWidthPx = bucketWidth,
                            pageWidth = page.width,
                            pageHeight = page.height
                        )
                        val bitmap = renderWithOomFallback(
                            page = page,
                            targetWidthPx = bucketWidth,
                            dims = primaryDims
                        )
                        if (bitmap != null && !closed.get()) {
                            pageBitmapCache.put(cacheKey, bitmap)
                        }
                        bitmap
                    } finally {
                        page.close()
                    }
                }
            }
        } finally {
            releaseOperation()
        }
    }

    private fun renderWithOomFallback(
        page: PdfRenderer.Page,
        targetWidthPx: Int,
        dims: PdfRenderSizing.RenderDimensions
    ): Bitmap? {
        return try {
            createAndRenderBitmap(page, dims.widthPx, dims.heightPx)
        } catch (_: OutOfMemoryError) {
            evictCaches()
            val reducedDims = PdfRenderSizing.computeSafeDimensions(
                targetWidthPx = targetWidthPx,
                pageWidth = page.width,
                pageHeight = page.height,
                scaleFactorOverride = 0.5f
            )
            try {
                createAndRenderBitmap(page, reducedDims.widthPx, reducedDims.heightPx)
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }

    private fun createAndRenderBitmap(
        page: PdfRenderer.Page,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    override suspend fun searchText(query: String): PdfSearchResult {
        if (!tryAcquireOperation()) {
            return PdfSearchResult(
                query = query.trim(),
                status = PdfSearchStatus.ERROR
            )
        }
        return try {
            withContext(dispatcherProvider.io) {
                searchMutex.withLock {
                    if (closed.get()) {
                        return@withLock PdfSearchResult(
                            query = query.trim(),
                            status = PdfSearchStatus.ERROR
                        )
                    }

                    PdfBoxInitializer.ensureInitialized(appContext)
                    val scratchDir = File(
                        appContext.cacheDir,
                        "pdfbox_scratch_${System.nanoTime()}"
                    ).apply { mkdirs() }
                    var document: PDDocument? = null
                    try {
                        val coroutineContext = currentCoroutineContext()
                        val memorySetting = MemoryUsageSetting
                            .setupMixed(MAX_PDFBOX_MAIN_MEMORY_BYTES)
                            .setTempDir(scratchDir)
                        document = PDDocument.load(preparedFile.file, memorySetting)
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
                            onCheckCancelled = {
                                coroutineContext.ensureActive()
                                if (closed.get()) {
                                    throw CancellationException("PDF session closed during search")
                                }
                            }
                        )
                    } finally {
                        runCatching { document?.close() }
                        runCatching { scratchDir.deleteRecursively() }
                    }
                }
            }
        } finally {
            releaseOperation()
        }
    }

    override fun evictCaches() {
        pageBitmapCache.evictAll()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        evictCaches()
        tryFinalizeResources()
    }

    private fun tryAcquireOperation(): Boolean {
        if (closed.get()) return false
        activeOperations.incrementAndGet()
        if (closed.get()) {
            releaseOperation()
            return false
        }
        return true
    }

    private fun releaseOperation() {
        if (activeOperations.decrementAndGet() <= 0 && closed.get()) {
            tryFinalizeResources()
        }
    }

    private fun tryFinalizeResources() {
        if (!closed.get() || activeOperations.get() > 0) return
        if (!resourcesReleased.compareAndSet(false, true)) return
        aspectRatioCache.clear()
        runCatching { pdfRenderer.close() }
        runCatching { parcelFileDescriptor.close() }
        if (preparedFile.deleteOnClose) {
            runCatching { preparedFile.file.delete() }
        }
    }

    private companion object {
        const val MAX_PDFBOX_MAIN_MEMORY_BYTES = 4L * 1024L * 1024L
    }
}
