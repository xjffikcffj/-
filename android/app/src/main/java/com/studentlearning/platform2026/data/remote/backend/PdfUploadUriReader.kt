package com.studentlearning.platform2026.data.remote.backend

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.security.MessageDigest

internal object PdfUploadUriReader {

    const val MAX_PDF_SIZE_BYTES = 100L * 1024L * 1024L
    private const val STREAM_BUFFER_BYTES = 16 * 1024
    private val PDF_MAGIC_BYTES = "%PDF-".toByteArray(Charsets.US_ASCII)

    suspend fun inspectSelectedPdf(
        contentResolver: ContentResolver,
        uri: Uri
    ): AppResult<PdfUploadBinarySource> {
        return try {
            val mimeType = contentResolver.getType(uri)?.trim()?.lowercase()
            if (mimeType != null &&
                mimeType != "application/pdf" &&
                mimeType != "application/octet-stream"
            ) {
                return invalidPdfError("Selected file type '$mimeType' is not a PDF document.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val headerProbe = ByteArray(PDF_MAGIC_BYTES.size)
            var headerFilled = 0
            val buffer = ByteArray(STREAM_BUFFER_BYTES)

            val inputStream = contentResolver.openInputStream(uri)
                ?: return invalidPdfError("Could not open selected PDF file stream.")

            inputStream.use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read == -1) break
                    if (headerFilled < PDF_MAGIC_BYTES.size) {
                        val copyLen = minOf(read, PDF_MAGIC_BYTES.size - headerFilled)
                        System.arraycopy(buffer, 0, headerProbe, headerFilled, copyLen)
                        headerFilled += copyLen
                    }
                    totalBytes += read
                    if (totalBytes > MAX_PDF_SIZE_BYTES) {
                        return invalidPdfError("Selected PDF exceeds the 100 MiB size limit.")
                    }
                    digest.update(buffer, 0, read)
                }
            }

            if (totalBytes <= 0L ||
                headerFilled < PDF_MAGIC_BYTES.size ||
                !headerProbe.contentEquals(PDF_MAGIC_BYTES)
            ) {
                return invalidPdfError("Selected file does not have a valid %PDF- header.")
            }

            val sha256Hex = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
            val displayName = resolveDisplayName(contentResolver, uri)

            AppResult.Success(
                PdfUploadBinarySource(
                    sizeBytes = totalBytes,
                    sha256Hex = sha256Hex,
                    displayName = displayName,
                    openInputStream = {
                        contentResolver.openInputStream(uri)
                            ?: throw IOException("Unable to re-open selected PDF stream.")
                    }
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            AppResult.Error(
                message = io.localizedMessage ?: "Failed reading selected PDF file.",
                cause = AdminException(
                    reason = AdminFailureReason.UNKNOWN,
                    message = io.localizedMessage,
                    cause = io
                )
            )
        } catch (throwable: Throwable) {
            AppResult.Error(
                message = throwable.localizedMessage ?: "Failed inspecting selected PDF file.",
                cause = AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.VERSION_UPLOAD_INVALID_PDF,
                    message = throwable.localizedMessage,
                    cause = throwable
                )
            )
        }
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (colIdx >= 0) cursor.getString(colIdx)?.trim()?.takeIf { it.isNotEmpty() } else null
                    } else {
                        null
                    }
                }
        } catch (_: Throwable) {
            null
        }
    }

    private fun invalidPdfError(message: String): AppResult.Error {
        return AppResult.Error(
            message = message,
            cause = AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_INVALID_PDF,
                message = message
            )
        )
    }
}
