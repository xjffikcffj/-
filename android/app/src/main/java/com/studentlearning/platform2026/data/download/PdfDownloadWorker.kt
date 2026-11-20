package com.studentlearning.platform2026.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_BYTES_DOWNLOADED
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_EXPECTED_SHA256
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_EXPECTED_SIZE
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_FAILURE_REASON
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_FILE_ID
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_FILE_TITLE
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_PROGRESS_PERCENT
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_SECTION_ID
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_TOTAL_BYTES
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_VERSION_ID
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_VERSION_LABEL
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_VERSION_NUMBER
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * WorkManager [CoroutineWorker] that performs resilient background PDF downloads independent of UI
 * screen lifecycle. Re-verifies authorization before starting work, aborts active transfers if the
 * user signs out mid-download, and ensures partial temp files are cleaned up on cancellation or
 * failure without touching previously completed downloads.
 */
class PdfDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val executionEngine: DownloadExecutionEngine
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val fileId = inputData.getString(KEY_FILE_ID)?.trim().orEmpty()
        val versionId = inputData.getString(KEY_VERSION_ID)?.trim().orEmpty()
        val fileTitle = inputData.getString(KEY_FILE_TITLE).orEmpty()
        val versionNumber = inputData.getInt(KEY_VERSION_NUMBER, -1).takeIf { it >= 1 }
        val versionLabel = inputData.getString(KEY_VERSION_LABEL)?.takeIf { it.isNotBlank() }
        val sectionId = inputData.getString(KEY_SECTION_ID)?.takeIf { it.isNotBlank() }
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, -1L).takeIf { it > 0L }
        val expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256)?.takeIf { it.isNotBlank() }

        if (fileId.isBlank() || versionId.isBlank()) {
            return failureResult(DownloadFailureReason.NO_PUBLISHED_VERSION)
        }

        // Enforce student/signed-in role requirement even at worker execution boundary.
        val initialSession = try {
            authRepository.requireAuthenticatedSession()
        } catch (_: AdminException) {
            return failureResult(DownloadFailureReason.UNAUTHENTICATED_GUEST)
        }
        if (!initialSession.isSignedIn || !authRepository.sessionFlow.value.isSignedIn) {
            return failureResult(DownloadFailureReason.UNAUTHENTICATED_GUEST)
        }
        if (!initialSession.canDownloadOfflineFiles || !authRepository.sessionFlow.value.canDownloadOfflineFiles) {
            return failureResult(DownloadFailureReason.UNAUTHORIZED_ROLE)
        }

        val request = OfflineDownloadRequest(
            fileId = fileId,
            versionId = versionId,
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            fileTitle = fileTitle,
            sectionId = sectionId,
            expectedFileSizeBytes = expectedSize,
            expectedChecksumSha256 = expectedSha256
        )

        val authTerminationReason = AtomicReference<DownloadFailureReason?>(null)

        return try {
            coroutineScope {
                val downloadJob = async {
                    executionEngine.executeDownload(
                        request = request,
                        ensureAuthorized = {
                            val current = try {
                                authRepository.requireAuthenticatedSession()
                            } catch (_: AdminException) {
                                throw DownloadException(
                                    reason = DownloadFailureReason.UNAUTHENTICATED_GUEST,
                                    message = "Student session is no longer active."
                                )
                            }
                            if (!current.isSignedIn || !authRepository.sessionFlow.value.isSignedIn) {
                                throw DownloadException(
                                    reason = DownloadFailureReason.UNAUTHENTICATED_GUEST,
                                    message = "Student signed out before completing download."
                                )
                            }
                            if (!current.canDownloadOfflineFiles ||
                                !authRepository.sessionFlow.value.canDownloadOfflineFiles
                            ) {
                                throw DownloadException(
                                    reason = DownloadFailureReason.UNAUTHORIZED_ROLE,
                                    message = "Account role is no longer permitted to download files."
                                )
                            }
                        }
                    ) { bytesDownloaded, totalBytes, percent ->
                        val progressBuilder = Data.Builder()
                            .putLong(KEY_BYTES_DOWNLOADED, bytesDownloaded)
                        if (totalBytes != null && totalBytes > 0L) {
                            progressBuilder.putLong(KEY_TOTAL_BYTES, totalBytes)
                        }
                        if (percent != null) {
                            progressBuilder.putInt(KEY_PROGRESS_PERCENT, percent)
                        }
                        if (versionNumber != null) {
                            progressBuilder.putInt(KEY_VERSION_NUMBER, versionNumber)
                        }
                        if (!versionLabel.isNullOrBlank()) {
                            progressBuilder.putString(KEY_VERSION_LABEL, versionLabel)
                        }
                        setProgress(progressBuilder.build())
                    }
                }

                val authWatchJob = launch {
                    authRepository.sessionFlow.collect { latestSession ->
                        if (!latestSession.isSignedIn) {
                            authTerminationReason.compareAndSet(
                                null,
                                DownloadFailureReason.UNAUTHENTICATED_GUEST
                            )
                            downloadJob.cancel(
                                CancellationException("Student signed out during active download.")
                            )
                        } else if (!latestSession.canDownloadOfflineFiles) {
                            authTerminationReason.compareAndSet(
                                null,
                                DownloadFailureReason.UNAUTHORIZED_ROLE
                            )
                            downloadJob.cancel(
                                CancellationException("Account role revoked download access.")
                            )
                        }
                    }
                }

                val saved = try {
                    downloadJob.await()
                } finally {
                    authWatchJob.cancel()
                }

                val successBuilder = Data.Builder()
                    .putLong(KEY_BYTES_DOWNLOADED, saved.fileSizeBytes)
                    .putLong(KEY_TOTAL_BYTES, saved.fileSizeBytes)
                    .putInt(KEY_PROGRESS_PERCENT, 100)
                if (saved.versionNumber != null) {
                    successBuilder.putInt(KEY_VERSION_NUMBER, saved.versionNumber)
                }
                if (!saved.versionLabel.isNullOrBlank()) {
                    successBuilder.putString(KEY_VERSION_LABEL, saved.versionLabel)
                }
                Result.success(successBuilder.build())
            }
        } catch (cancelled: CancellationException) {
            val forcedAuthFailure = authTerminationReason.get()
            if (forcedAuthFailure != null) {
                failureResult(forcedAuthFailure)
            } else {
                throw cancelled
            }
        } catch (downloadError: DownloadException) {
            failureResult(downloadError.reason)
        } catch (_: Throwable) {
            failureResult(DownloadFailureReason.UNKNOWN)
        }
    }

    private fun failureResult(reason: DownloadFailureReason): Result {
        return Result.failure(
            Data.Builder()
                .putString(KEY_FAILURE_REASON, reason.name)
                .build()
        )
    }
}
