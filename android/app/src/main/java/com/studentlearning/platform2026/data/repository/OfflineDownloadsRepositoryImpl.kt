package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.download.DownloadTaskScheduler
import com.studentlearning.platform2026.data.download.LocalPdfStorageManager
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.OfflineDownloadsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OfflineDownloadsRepositoryImpl(
    private val authRepository: AuthRepository,
    private val downloadsDataSource: OfflineDownloadsDataSource,
    private val storageManager: LocalPdfStorageManager,
    private val taskScheduler: DownloadTaskScheduler,
    private val dispatcherProvider: DispatcherProvider,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() }
) : OfflineDownloadsRepository {

    private data class PendingTaskHint(
        val state: DownloadTaskState,
        val supersededScheduledTaskId: String? = null,
        val baselineScheduledState: DownloadTaskState? = null
    )

    private val operationMutex = Mutex()
    private val immediateTaskHints = MutableStateFlow<Map<Pair<String, String>, PendingTaskHint>>(emptyMap())

    override fun observeAllSavedVersions(): Flow<List<LocalDownloadedFileVersion>> {
        return downloadsDataSource.observeAllRecords()
            .map { records ->
                withContext(dispatcherProvider.io) {
                    records.filter { storageManager.isStoredFileValid(it) }
                }
            }
    }

    override fun observeFileOfflineState(
        fileId: String,
        activeVersionId: String?
    ): Flow<StudyFileOfflineState> {
        return combine(
            observeAllSavedVersions(),
            taskScheduler.observeTasks(),
            immediateTaskHints
        ) { savedRecords, scheduledTasks, hints ->
            buildStudyFileOfflineState(
                fileId = fileId,
                activeVersionId = activeVersionId,
                savedRecords = savedRecords,
                scheduledTasks = scheduledTasks,
                hints = hints
            )
        }
    }

    override fun observeSectionOfflineStates(
        files: List<StudyFileMetadata>
    ): Flow<Map<String, StudyFileOfflineState>> {
        return combine(
            observeAllSavedVersions(),
            taskScheduler.observeTasks(),
            immediateTaskHints
        ) { savedRecords, scheduledTasks, hints ->
            files.associate { file ->
                file.id to buildStudyFileOfflineState(
                    fileId = file.id,
                    activeVersionId = file.activeVersionId,
                    savedRecords = savedRecords,
                    scheduledTasks = scheduledTasks,
                    hints = hints
                )
            }
        }
    }

    override suspend fun getVerifiedLocalVersion(
        fileId: String,
        versionId: String?
    ): LocalDownloadedFileVersion? = withContext(dispatcherProvider.io) {
        if (fileId.isBlank()) return@withContext null
        if (!versionId.isNullOrBlank()) {
            val exact = downloadsDataSource.getRecord(fileId, versionId) ?: return@withContext null
            return@withContext exact.takeIf { storageManager.isStoredFileValid(it) }
        }
        downloadsDataSource.getRecordsForFile(fileId)
            .firstOrNull { storageManager.isStoredFileValid(it) }
    }

    override suspend fun getSavedVersionsForFile(fileId: String): List<LocalDownloadedFileVersion> =
        withContext(dispatcherProvider.io) {
            if (fileId.isBlank()) return@withContext emptyList()
            downloadsDataSource.getRecordsForFile(fileId)
                .filter { storageManager.isStoredFileValid(it) }
        }

    override suspend fun getSavedVersionsForSection(sectionId: String): List<LocalDownloadedFileVersion> =
        withContext(dispatcherProvider.io) {
            if (sectionId.isBlank()) return@withContext emptyList()
            downloadsDataSource.getAllRecords()
                .filter { it.sectionId == sectionId && storageManager.isStoredFileValid(it) }
        }

    override suspend fun startDownload(
        file: StudyFileMetadata,
        expectedFileSizeBytes: Long?,
        expectedChecksumSha256: String?
    ): AppResult<DownloadTaskState> {
        val request = OfflineDownloadRequest(
            fileId = file.id,
            versionId = file.activeVersionId.orEmpty(),
            versionNumber = file.activeVersionNumber,
            versionLabel = file.activeVersionLabel,
            fileTitle = file.title,
            sectionId = file.sectionId,
            expectedFileSizeBytes = expectedFileSizeBytes,
            expectedChecksumSha256 = expectedChecksumSha256
        )
        return startDownloadInternal(request = request, replaceExisting = false)
    }

    override suspend fun startDownload(request: OfflineDownloadRequest): AppResult<DownloadTaskState> {
        return startDownloadInternal(request = request, replaceExisting = false)
    }

    override suspend fun retryDownload(
        file: StudyFileMetadata,
        expectedFileSizeBytes: Long?,
        expectedChecksumSha256: String?
    ): AppResult<DownloadTaskState> {
        val request = OfflineDownloadRequest(
            fileId = file.id,
            versionId = file.activeVersionId.orEmpty(),
            versionNumber = file.activeVersionNumber,
            versionLabel = file.activeVersionLabel,
            fileTitle = file.title,
            sectionId = file.sectionId,
            expectedFileSizeBytes = expectedFileSizeBytes,
            expectedChecksumSha256 = expectedChecksumSha256
        )
        return startDownloadInternal(request = request, replaceExisting = true)
    }

    private suspend fun startDownloadInternal(
        request: OfflineDownloadRequest,
        replaceExisting: Boolean
    ): AppResult<DownloadTaskState> = withContext(dispatcherProvider.io) {
        try {
            // Strict server-role/session gate: unregistered guests cannot start downloads even via direct calls.
            val currentSnapshot = authRepository.sessionFlow.value
            val session = try {
                authRepository.requireAuthenticatedSession()
            } catch (_: AdminException) {
                return@withContext errorResult(
                    reason = DownloadFailureReason.UNAUTHENTICATED_GUEST,
                    message = "Signing in with a student account is required to download files."
                )
            }

            if (!session.isSignedIn || !currentSnapshot.isSignedIn) {
                return@withContext errorResult(
                    reason = DownloadFailureReason.UNAUTHENTICATED_GUEST,
                    message = "Signing in with a student account is required to download files."
                )
            }

            if (!session.canDownloadOfflineFiles || !currentSnapshot.canDownloadOfflineFiles) {
                return@withContext errorResult(
                    reason = DownloadFailureReason.UNAUTHORIZED_ROLE,
                    message = "Current account role is not permitted to download offline files."
                )
            }

            if (request.fileId.isBlank()) {
                return@withContext errorResult(
                    reason = DownloadFailureReason.UNKNOWN,
                    message = "Study file identifier is missing."
                )
            }

            if (request.versionId.isBlank()) {
                return@withContext errorResult(
                    reason = DownloadFailureReason.NO_PUBLISHED_VERSION,
                    message = "Study file does not have an active published version yet."
                )
            }

            operationMutex.withLock {
                val key = request.fileId to request.versionId

                // Prevent duplicate download if this exact version is already completed & verified locally.
                val existingCompleted = downloadsDataSource.getRecord(request.fileId, request.versionId)
                if (existingCompleted != null && storageManager.isStoredFileValid(existingCompleted)) {
                    val completedState = DownloadTaskState(
                        fileId = request.fileId,
                        versionId = request.versionId,
                        versionNumber = request.versionNumber ?: existingCompleted.versionNumber,
                        versionLabel = request.versionLabel ?: existingCompleted.versionLabel,
                        status = DownloadStatus.COMPLETED,
                        bytesDownloaded = existingCompleted.fileSizeBytes,
                        totalBytes = existingCompleted.fileSizeBytes,
                        progressPercent = 100,
                        failureReason = null,
                        updatedAtEpochMillis = existingCompleted.downloadedAtEpochMillis
                    )
                    immediateTaskHints.update { it - key }
                    return@withContext AppResult.Success(completedState)
                }

                // Prevent duplicate concurrent download if already genuinely queued/running for the same version.
                val liveTask = taskScheduler.getTaskState(request.fileId, request.versionId)
                val currentHint = immediateTaskHints.value[key]
                val isCancellingStaleLiveTask = currentHint?.state?.status == DownloadStatus.CANCELLED &&
                    liveTask != null &&
                    liveTask.status.isInProgress &&
                    (liveTask.taskId == null || liveTask.taskId == currentHint.state.taskId)

                val effectivelyInProgress = !replaceExisting &&
                    !isCancellingStaleLiveTask &&
                    (
                        (liveTask != null && liveTask.status.isInProgress) ||
                            (liveTask == null && currentHint?.state?.status?.isInProgress == true)
                        )

                if (effectivelyInProgress) {
                    val activeState = liveTask ?: currentHint!!.state
                    immediateTaskHints.update {
                        it + (key to PendingTaskHint(state = activeState))
                    }
                    return@withContext AppResult.Success(activeState)
                }

                // Force REPLACE if retrying or starting immediately over a cancelling/terminal task
                // so a lingering WorkManager state never blocks a fresh download attempt.
                val shouldReplace = replaceExisting ||
                    isCancellingStaleLiveTask ||
                    (liveTask != null && !liveTask.status.isInProgress)

                val enqueuedState = taskScheduler.enqueue(
                    request = request,
                    replaceExisting = shouldReplace
                )
                if (enqueuedState.status.isInProgress) {
                    immediateTaskHints.update {
                        it + (
                            key to PendingTaskHint(
                                state = enqueuedState,
                                supersededScheduledTaskId = liveTask?.taskId,
                                baselineScheduledState = liveTask
                            )
                            )
                    }
                } else {
                    immediateTaskHints.update { it - key }
                }
                AppResult.Success(enqueuedState)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (downloadError: DownloadException) {
            errorResult(
                reason = downloadError.reason,
                message = downloadError.message ?: "Download failed.",
                cause = downloadError
            )
        } catch (throwable: Throwable) {
            errorResult(
                reason = DownloadFailureReason.UNKNOWN,
                message = throwable.localizedMessage ?: "Unexpected error starting download.",
                cause = throwable
            )
        }
    }

    override suspend fun cancelDownload(fileId: String, versionId: String): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            if (fileId.isBlank() || versionId.isBlank()) {
                return@withContext AppResult.Success(Unit)
            }
            operationMutex.withLock {
                val key = fileId to versionId
                val liveBeforeCancel = taskScheduler.getTaskState(fileId, versionId)
                taskScheduler.cancel(fileId, versionId)

                // If the download committed right before/during cancel, preserve the completed copy
                // and do not mask COMPLETED with a CANCELLED hint.
                val alreadyCompleted = downloadsDataSource.getRecord(fileId, versionId)
                    ?.takeIf { storageManager.isStoredFileValid(it) }
                if (alreadyCompleted != null) {
                    immediateTaskHints.update { it - key }
                    return@withContext AppResult.Success(Unit)
                }

                immediateTaskHints.update {
                    it + (
                        key to PendingTaskHint(
                            state = DownloadTaskState(
                                fileId = fileId,
                                versionId = versionId,
                                status = DownloadStatus.CANCELLED,
                                updatedAtEpochMillis = clockEpochMillis(),
                                taskId = liveBeforeCancel?.taskId
                            )
                        )
                        )
                }
                AppResult.Success(Unit)
            }
        }

    override suspend fun deleteLocalVersion(fileId: String, versionId: String): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            if (fileId.isBlank() || versionId.isBlank()) {
                return@withContext AppResult.Success(Unit)
            }
            operationMutex.withLock {
                taskScheduler.cancel(fileId, versionId)
                storageManager.deleteCompletedVersionFile(fileId, versionId)
                downloadsDataSource.removeRecord(fileId, versionId)
                immediateTaskHints.update { it - (fileId to versionId) }
                AppResult.Success(Unit)
            }
        }

    override suspend fun cleanupOrphanedTempFiles() {
        withContext(dispatcherProvider.io) {
            storageManager.cleanupOrphanedTempFiles()
        }
    }

    private fun buildStudyFileOfflineState(
        fileId: String,
        activeVersionId: String?,
        savedRecords: List<LocalDownloadedFileVersion>,
        scheduledTasks: Map<Pair<String, String>, DownloadTaskState>,
        hints: Map<Pair<String, String>, PendingTaskHint>
    ): StudyFileOfflineState {
        val savedForFile = savedRecords.filter { it.fileId == fileId }
        val activeTask = if (activeVersionId.isNullOrBlank()) {
            null
        } else {
            val key = fileId to activeVersionId
            val activeSaved = savedForFile.firstOrNull { it.versionId == activeVersionId }
            val scheduled = scheduledTasks[key]
                ?.takeUnless { it.status == DownloadStatus.COMPLETED && activeSaved == null }
            val hint = hints[key]
            when {
                activeSaved != null -> DownloadTaskState(
                    fileId = fileId,
                    versionId = activeVersionId,
                    versionNumber = activeSaved.versionNumber,
                    versionLabel = activeSaved.versionLabel,
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = activeSaved.fileSizeBytes,
                    totalBytes = activeSaved.fileSizeBytes,
                    progressPercent = 100,
                    updatedAtEpochMillis = activeSaved.downloadedAtEpochMillis
                )
                scheduled != null && hint != null -> resolveScheduledVsHint(scheduled, hint)
                scheduled != null -> scheduled
                else -> hint?.state
            }
        }

        return StudyFileOfflineState(
            fileId = fileId,
            activeVersionId = activeVersionId,
            activeVersionDownload = activeTask,
            savedVersions = savedForFile
        )
    }

    private fun resolveScheduledVsHint(
        scheduled: DownloadTaskState,
        hint: PendingTaskHint
    ): DownloadTaskState {
        val hintState = hint.state
        if (hintState.status == DownloadStatus.CANCELLED && scheduled.status.isInProgress) {
            val refersToSameTask = hintState.taskId == null ||
                scheduled.taskId == null ||
                scheduled.taskId == hintState.taskId
            return if (refersToSameTask) hintState else scheduled
        }

        if (hintState.status == DownloadStatus.QUEUED) {
            val matchesSupersededTask = hint.supersededScheduledTaskId != null &&
                scheduled.taskId != null &&
                scheduled.taskId == hint.supersededScheduledTaskId
            val matchesStaleTerminalBaseline = scheduled.taskId == null &&
                (scheduled.status == DownloadStatus.FAILED || scheduled.status == DownloadStatus.CANCELLED) &&
                hint.baselineScheduledState != null &&
                scheduled == hint.baselineScheduledState
            if (matchesSupersededTask || matchesStaleTerminalBaseline) {
                return hintState
            }
        }

        return scheduled
    }

    private fun errorResult(
        reason: DownloadFailureReason,
        message: String,
        cause: Throwable? = null
    ): AppResult.Error {
        val exception = if (cause is DownloadException) {
            cause
        } else {
            DownloadException(reason = reason, message = message, cause = cause)
        }
        return AppResult.Error(message = message, cause = exception)
    }
}
