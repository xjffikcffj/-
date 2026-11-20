package com.studentlearning.platform2026.data.download

import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes a verified, version-isolated download of a study PDF into app-private storage.
 *
 * Guarantees:
 * - Skips redundant network download if the requested `(fileId, versionId)` is already completed
 *   and valid on disk.
 * - Checks free storage space before and during transfer.
 * - Writes exclusively to a temporary `.part` file until byte count, `%PDF-` header, and SHA-256
 *   verification succeed.
 * - Cleans up temporary `.part` files on cancellation or any failure without touching existing
 *   completed versions.
 */
class DownloadExecutionEngine(
    private val sourceDataSource: PdfFileSourceDataSource,
    private val streamFetcher: DownloadStreamFetcher,
    private val storageManager: LocalPdfStorageManager,
    private val downloadsDataSource: OfflineDownloadsDataSource,
    private val dispatcherProvider: DispatcherProvider,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun executeDownload(
        request: OfflineDownloadRequest,
        ensureAuthorized: suspend () -> Unit = {},
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?, progressPercent: Int?) -> Unit = { _, _, _ -> }
    ): LocalDownloadedFileVersion = withContext(dispatcherProvider.io) {
        if (request.fileId.isBlank()) {
            throw DownloadException(
                reason = DownloadFailureReason.UNKNOWN,
                message = "Study file identifier is missing."
            )
        }
        if (request.versionId.isBlank()) {
            throw DownloadException(
                reason = DownloadFailureReason.NO_PUBLISHED_VERSION,
                message = "Study file does not have an active published version yet."
            )
        }

        ensureAuthorized()

        // Sweep any leftover `.part` or `.commit_*.tmp` orphans from dead processes while
        // preserving all currently registered in-flight temp files and completed PDFs.
        storageManager.cleanupOrphanedTempFiles()

        // Prevent redundant duplicate downloads when this exact version is already verified on disk.
        val existingRecord = downloadsDataSource.getRecord(request.fileId, request.versionId)
        if (existingRecord != null && storageManager.isStoredFileValid(existingRecord)) {
            onProgress(existingRecord.fileSizeBytes, existingRecord.fileSizeBytes, 100)
            return@withContext existingRecord
        }

        val source = try {
            sourceDataSource.resolvePdfSource(
                fileId = request.fileId,
                activeVersionId = request.versionId
            )
        } catch (pdfError: PdfLoadException) {
            throw DownloadException(
                reason = mapPdfFailure(pdfError.reason),
                message = pdfError.message,
                cause = pdfError
            )
        }

        val remoteSource = when (source) {
            is PdfSourceDescriptor.AuthorizedRemoteUrl -> source
            is PdfSourceDescriptor.LocalFilePath -> {
                throw DownloadException(
                    reason = DownloadFailureReason.SECURE_BROKER_NOT_CONFIGURED,
                    message = "Remote download descriptor is required."
                )
            }
        }

        storageManager.ensureSufficientStorage(request.expectedFileSizeBytes)

        var tempPartFile: File? = null
        var committed = false

        try {
            tempPartFile = storageManager.createTempPartFile(
                fileId = request.fileId,
                versionId = request.versionId
            )

            val streamResult = streamFetcher.downloadToFile(
                source = remoteSource,
                destinationFile = tempPartFile,
                onContentLengthKnown = { contentLengthBytes ->
                    val requiredBytes = request.expectedFileSizeBytes ?: contentLengthBytes
                    storageManager.ensureSufficientStorage(requiredBytes)
                },
                onProgress = { bytesDownloaded, streamTotalBytes ->
                    val effectiveTotal = request.expectedFileSizeBytes
                        ?.takeIf { it > 0L }
                        ?: streamTotalBytes?.takeIf { it > 0L }
                    val percent = if (effectiveTotal != null && effectiveTotal > 0L) {
                        ((bytesDownloaded.coerceAtMost(effectiveTotal) * 100L) / effectiveTotal)
                            .toInt()
                            .coerceIn(0, 100)
                    } else {
                        null
                    }
                    onProgress(bytesDownloaded, effectiveTotal, percent)
                }
            )

            val expectedSize = request.expectedFileSizeBytes?.takeIf { it > 0L }
                ?: streamResult.contentLengthHeaderBytes?.takeIf { it > 0L }
            val expectedSha = request.expectedChecksumSha256?.trim()?.takeIf { it.isNotEmpty() }
                ?: streamResult.serverSha256Header

            val verified = storageManager.verifyDownloadedTempFile(
                tempFile = tempPartFile,
                expectedByteCount = expectedSize,
                expectedSha256 = expectedSha
            )

            if (streamResult.bytesWritten != verified.fileSizeBytes) {
                throw DownloadException(
                    reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                    message = "Streamed byte count does not match file size on disk."
                )
            }

            ensureAuthorized()

            val completedFile = storageManager.commitVerifiedTempFile(
                tempFile = tempPartFile,
                fileId = request.fileId,
                versionId = request.versionId
            )

            val savedRecord = LocalDownloadedFileVersion(
                fileId = request.fileId,
                versionId = request.versionId,
                versionNumber = request.versionNumber,
                versionLabel = request.versionLabel,
                fileTitle = request.fileTitle,
                sectionId = request.sectionId,
                localFilePath = completedFile.absolutePath,
                fileSizeBytes = verified.fileSizeBytes,
                checksumSha256 = verified.sha256Hex,
                downloadedAtEpochMillis = clockEpochMillis()
            )

            withContext(NonCancellable) {
                downloadsDataSource.saveCompletedRecord(savedRecord)
            }
            committed = true
            onProgress(verified.fileSizeBytes, verified.fileSizeBytes, 100)
            return@withContext savedRecord
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (downloadError: DownloadException) {
            throw downloadError
        } catch (throwable: Throwable) {
            val reason = if (LocalPdfStorageManager.isOutOfSpaceError(throwable)) {
                DownloadFailureReason.INSUFFICIENT_STORAGE
            } else {
                DownloadFailureReason.UNKNOWN
            }
            throw DownloadException(
                reason = reason,
                message = throwable.localizedMessage ?: "Unexpected download failure.",
                cause = throwable
            )
        } finally {
            storageManager.releaseTempFile(tempPartFile)
            if (!committed) {
                storageManager.deleteTempFileQuietly(tempPartFile)
            }
        }
    }

    private fun mapPdfFailure(reason: PdfFailureReason): DownloadFailureReason = when (reason) {
        PdfFailureReason.NO_PUBLISHED_VERSION -> DownloadFailureReason.NO_PUBLISHED_VERSION
        PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED -> DownloadFailureReason.SECURE_BROKER_NOT_CONFIGURED
        PdfFailureReason.UNAUTHORIZED_ACCESS -> DownloadFailureReason.UNAUTHORIZED_ACCESS
        PdfFailureReason.NETWORK_UNAVAILABLE -> DownloadFailureReason.NETWORK_UNAVAILABLE
        PdfFailureReason.FILE_NOT_FOUND -> DownloadFailureReason.SERVER_ERROR
        PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE -> DownloadFailureReason.INTEGRITY_CHECK_FAILED
        PdfFailureReason.UNKNOWN -> DownloadFailureReason.UNKNOWN
    }
}
