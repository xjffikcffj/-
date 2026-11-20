package com.studentlearning.platform2026.data.pdf

import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

data class PreparedPdfFile(
    val file: File,
    val deleteOnClose: Boolean
)

interface PdfContentFetcher {
    suspend fun prepareReadableFile(
        source: PdfSourceDescriptor,
        fileId: String
    ): PreparedPdfFile
}

/**
 * Streams an authorized online PDF into an ephemeral viewer session buffer inside [sessionCacheDir]
 * (deleted on session close; NOT an offline download manager).
 */
class HttpPdfContentFetcher(
    private val sessionCacheDir: File
) : PdfContentFetcher {

    private val tempFileSequence = AtomicLong(0L)

    override suspend fun prepareReadableFile(
        source: PdfSourceDescriptor,
        fileId: String
    ): PreparedPdfFile {
        return when (source) {
            is PdfSourceDescriptor.LocalFilePath -> {
                val localFile = File(source.absolutePath)
                if (!localFile.exists() || !localFile.isFile || !localFile.canRead()) {
                    throw PdfLoadException(
                        reason = PdfFailureReason.FILE_NOT_FOUND,
                        message = "PDF file is unavailable on device."
                    )
                }
                if (!hasValidPdfHeader(localFile)) {
                    throw PdfLoadException(
                        reason = PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE,
                        message = "File does not contain a valid PDF header."
                    )
                }
                PreparedPdfFile(file = localFile, deleteOnClose = source.deleteOnSessionClose)
            }

            is PdfSourceDescriptor.AuthorizedRemoteUrl -> {
                fetchRemoteHttpsPdf(source)
            }
        }
    }

    private suspend fun fetchRemoteHttpsPdf(
        source: PdfSourceDescriptor.AuthorizedRemoteUrl
    ): PreparedPdfFile {
        if (!source.url.startsWith("https://", ignoreCase = true)) {
            throw PdfLoadException(
                reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
                message = "Only HTTPS sources are permitted for remote PDF streaming."
            )
        }

        if (!sessionCacheDir.exists()) {
            sessionCacheDir.mkdirs()
        }
        val safeKey = source.cacheKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(48)
        val seq = tempFileSequence.incrementAndGet()
        val tempFile = File(sessionCacheDir, "viewer_${safeKey}_${seq}_${System.nanoTime()}.pdf")
        var connection: HttpURLConnection? = null
        var cancellationHook: DisposableHandle? = null

        try {
            currentCoroutineContext().ensureActive()
            val url = URL(source.url)
            val activeConnection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                source.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            connection = activeConnection
            cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    runCatching { activeConnection.disconnect() }
                }
            }

            val statusCode = activeConnection.responseCode
            when (statusCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    throw PdfLoadException(
                        reason = PdfFailureReason.UNAUTHORIZED_ACCESS,
                        message = "Access denied by remote PDF source (HTTP $statusCode)."
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    throw PdfLoadException(
                        reason = PdfFailureReason.FILE_NOT_FOUND,
                        message = "Remote PDF file was not found (HTTP 404)."
                    )
                }
                else -> {
                    throw PdfLoadException(
                        reason = PdfFailureReason.NETWORK_UNAVAILABLE,
                        message = "Remote PDF fetch failed with HTTP $statusCode."
                    )
                }
            }

            activeConnection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            if (!hasValidPdfHeader(tempFile)) {
                tempFile.delete()
                throw PdfLoadException(
                    reason = PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE,
                    message = "Downloaded stream is not a valid PDF document."
                )
            }

            return PreparedPdfFile(file = tempFile, deleteOnClose = true)
        } catch (cancelled: CancellationException) {
            tempFile.delete()
            throw cancelled
        } catch (pdfError: PdfLoadException) {
            tempFile.delete()
            throw pdfError
        } catch (io: IOException) {
            tempFile.delete()
            currentCoroutineContext().ensureActive()
            throw PdfLoadException(
                reason = PdfFailureReason.NETWORK_UNAVAILABLE,
                message = io.localizedMessage ?: "Network error while loading PDF.",
                cause = io
            )
        } catch (unexpected: Throwable) {
            tempFile.delete()
            throw PdfLoadException(
                reason = PdfFailureReason.UNKNOWN,
                message = unexpected.localizedMessage ?: "Unexpected error loading PDF.",
                cause = unexpected
            )
        } finally {
            cancellationHook?.dispose()
            connection?.disconnect()
        }
    }

    internal fun hasValidPdfHeader(file: File): Boolean {
        if (!file.exists() || file.length() < PDF_MAGIC_BYTES.size) return false
        return try {
            FileInputStream(file).use { stream ->
                val header = ByteArray(PDF_MAGIC_BYTES.size)
                val read = stream.read(header)
                read == PDF_MAGIC_BYTES.size && header.contentEquals(PDF_MAGIC_BYTES)
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val STREAM_BUFFER_BYTES = 16 * 1024
        val PDF_MAGIC_BYTES = "%PDF-".toByteArray(Charsets.US_ASCII)
    }
}
