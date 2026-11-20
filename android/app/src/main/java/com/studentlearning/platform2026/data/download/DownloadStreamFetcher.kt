package com.studentlearning.platform2026.data.download

import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class DownloadStreamResult(
    val bytesWritten: Long,
    val contentLengthHeaderBytes: Long?,
    val serverSha256Header: String?
)

interface DownloadStreamFetcher {
    suspend fun downloadToFile(
        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
        destinationFile: File,
        onContentLengthKnown: suspend (contentLengthBytes: Long) -> Unit = {},
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): DownloadStreamResult
}

/**
 * Streams an authorized HTTPS PDF source into [destinationFile] with active cancellation,
 * progress reporting, HTTP status classification, and write/disk-space error separation.
 *
 * Security & Redirect Policy:
 * - `instanceFollowRedirects = false` is intentionally enforced so downloads never silently follow
 *   3xx redirects to unvalidated hosts or downgrade HTTPS to HTTP.
 * - The future Cloudflare R2 broker endpoint must stream PDF bytes directly over HTTPS with HTTP 200
 *   (or supply a direct HTTPS URL to the fetcher via broker API JSON rather than HTTP 301/302/307).
 */
class HttpDownloadStreamFetcher(
    private val allowInsecureHttpForTesting: Boolean = false
) : DownloadStreamFetcher {

    override suspend fun downloadToFile(
        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
        destinationFile: File,
        onContentLengthKnown: suspend (contentLengthBytes: Long) -> Unit,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit
    ): DownloadStreamResult {
        val isHttps = source.url.startsWith("https://", ignoreCase = true)
        val isAllowedHttp = allowInsecureHttpForTesting &&
            source.url.startsWith("http://", ignoreCase = true)
        if (!isHttps && !isAllowedHttp) {
            throw DownloadException(
                reason = DownloadFailureReason.SECURE_BROKER_NOT_CONFIGURED,
                message = "Only HTTPS sources are permitted for offline PDF downloads."
            )
        }

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
                    throw DownloadException(
                        reason = DownloadFailureReason.UNAUTHORIZED_ACCESS,
                        message = "Access denied by remote PDF source (HTTP $statusCode)."
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    throw DownloadException(
                        reason = DownloadFailureReason.SERVER_ERROR,
                        message = "Remote PDF file was not found (HTTP 404)."
                    )
                }
                else -> {
                    throw DownloadException(
                        reason = DownloadFailureReason.SERVER_ERROR,
                        message = "Remote PDF download failed with HTTP $statusCode."
                    )
                }
            }

            val rawContentLength = activeConnection.contentLengthLong
            val knownTotalBytes = rawContentLength.takeIf { it > 0L }
            if (knownTotalBytes != null) {
                onContentLengthKnown(knownTotalBytes)
            }

            val rawHeaderChecksum = activeConnection.getHeaderField("X-Content-Sha256")
            val headerChecksum = if (rawHeaderChecksum != null) {
                val trimmed = rawHeaderChecksum.trim().lowercase()
                if (trimmed.isEmpty()) {
                    null
                } else if (!trimmed.matches(SHA256_HEX_REGEX)) {
                    throw DownloadException(
                        reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                        message = "Malformed X-Content-Sha256 response header."
                    )
                } else {
                    trimmed
                }
            } else {
                null
            }

            var bytesWritten = 0L
            var lastProgressBytes = 0L
            onProgress(0L, knownTotalBytes)

            try {
                activeConnection.inputStream.use { input ->
                    try {
                        FileOutputStream(destinationFile).use { output ->
                            val buffer = ByteArray(STREAM_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = try {
                                    input.read(buffer)
                                } catch (io: IOException) {
                                    currentCoroutineContext().ensureActive()
                                    throw DownloadException(
                                        reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                                        message = io.localizedMessage ?: "Network interrupted during download.",
                                        cause = io
                                    )
                                }
                                if (read == -1) break
                                try {
                                    output.write(buffer, 0, read)
                                } catch (io: IOException) {
                                    val reason = if (LocalPdfStorageManager.isOutOfSpaceError(io)) {
                                        DownloadFailureReason.INSUFFICIENT_STORAGE
                                    } else {
                                        DownloadFailureReason.STORAGE_WRITE_ERROR
                                    }
                                    throw DownloadException(
                                        reason = reason,
                                        message = io.localizedMessage ?: "Failed writing downloaded bytes.",
                                        cause = io
                                    )
                                }
                                bytesWritten += read
                                if (bytesWritten - lastProgressBytes >= PROGRESS_NOTIFY_STEP_BYTES ||
                                    (knownTotalBytes != null && bytesWritten >= knownTotalBytes)
                                ) {
                                    lastProgressBytes = bytesWritten
                                    onProgress(bytesWritten, knownTotalBytes)
                                }
                            }
                            try {
                                output.flush()
                                output.fd.sync()
                            } catch (io: IOException) {
                                val reason = if (LocalPdfStorageManager.isOutOfSpaceError(io)) {
                                    DownloadFailureReason.INSUFFICIENT_STORAGE
                                } else {
                                    DownloadFailureReason.STORAGE_WRITE_ERROR
                                }
                                throw DownloadException(
                                    reason = reason,
                                    message = io.localizedMessage ?: "Failed flushing downloaded file.",
                                    cause = io
                                )
                            }
                        }
                    } catch (downloadError: DownloadException) {
                        throw downloadError
                    } catch (io: IOException) {
                        val reason = if (LocalPdfStorageManager.isOutOfSpaceError(io)) {
                            DownloadFailureReason.INSUFFICIENT_STORAGE
                        } else {
                            DownloadFailureReason.STORAGE_WRITE_ERROR
                        }
                        throw DownloadException(
                            reason = reason,
                            message = io.localizedMessage ?: "Unable to open destination file for writing.",
                            cause = io
                        )
                    }
                }
            } catch (downloadError: DownloadException) {
                throw downloadError
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                currentCoroutineContext().ensureActive()
                throw DownloadException(
                    reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                    message = io.localizedMessage ?: "Network connection lost during download.",
                    cause = io
                )
            }

            if (bytesWritten != lastProgressBytes) {
                onProgress(bytesWritten, knownTotalBytes)
            }

            return DownloadStreamResult(
                bytesWritten = bytesWritten,
                contentLengthHeaderBytes = knownTotalBytes,
                serverSha256Header = headerChecksum
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (downloadError: DownloadException) {
            throw downloadError
        } catch (io: IOException) {
            currentCoroutineContext().ensureActive()
            throw DownloadException(
                reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                message = io.localizedMessage ?: "Network error connecting to PDF download source.",
                cause = io
            )
        } catch (unexpected: Throwable) {
            throw DownloadException(
                reason = DownloadFailureReason.UNKNOWN,
                message = unexpected.localizedMessage ?: "Unexpected error downloading PDF.",
                cause = unexpected
            )
        } finally {
            cancellationHook?.dispose()
            connection?.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val PROGRESS_NOTIFY_STEP_BYTES = 32 * 1024L
        val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
