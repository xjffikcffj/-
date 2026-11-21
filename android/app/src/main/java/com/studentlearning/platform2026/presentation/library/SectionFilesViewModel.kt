package com.studentlearning.platform2026.presentation.library

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.NewVersionBadgePolicy
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import com.studentlearning.platform2026.domain.repository.OfflineDownloadsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SectionFilesViewModel(
    private val sectionId: String,
    private val catalogRepository: CatalogRepository,
    private val authRepository: AuthRepository? = null,
    private val offlineDownloadsRepository: OfflineDownloadsRepository? = null,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val monotonicElapsedMillis: (() -> Long)? = null
) : ViewModel() {

    private sealed interface CatalogSnapshot {
        data object Loading : CatalogSnapshot
        data object Empty : CatalogSnapshot
        data class Loaded(val files: List<StudyFileMetadata>) : CatalogSnapshot
        data class Failed(
            val reason: CatalogFailureReason,
            val offlineSavedFiles: List<LocalDownloadedFileVersion>
        ) : CatalogSnapshot
    }

    private val catalogSnapshot = MutableStateFlow<CatalogSnapshot>(CatalogSnapshot.Loading)
    private val guestPromptFileTitle = MutableStateFlow<String?>(null)
    private val transientActionErrorReason = MutableStateFlow<DownloadFailureReason?>(null)
    private val badgeExpiryTrigger = MutableStateFlow(0L)

    private var badgeExpiryJob: Job? = null
    private var scheduledExpiryTargetEpochMillis: Long? = null
    private var anchorWallEpochMillis: Long? = null
    private var anchorMonotonicMillis: Long? = null

    private val _uiState = MutableStateFlow<SectionFilesUiState>(SectionFilesUiState.Loading)
    val uiState: StateFlow<SectionFilesUiState> = _uiState.asStateFlow()

    init {
        observeUiState()
        loadFiles()
    }

    fun retry() {
        loadFiles()
    }

    fun onForegroundResumed() {
        cancelBadgeExpiryJob()
        badgeExpiryTrigger.value = badgeExpiryTrigger.value + 1L
    }

    fun startDownload(file: StudyFileMetadata) {
        val session = authRepository?.sessionFlow?.value
        if (session == null || !session.canDownloadOfflineFiles) {
            guestPromptFileTitle.value = file.title
            return
        }
        val downloadsRepo = offlineDownloadsRepository ?: return
        transientActionErrorReason.value = null

        viewModelScope.launch {
            val result = downloadsRepo.startDownload(file = file)
            if (result is AppResult.Error) {
                val reason = (result.cause as? DownloadException)?.reason
                    ?: DownloadFailureReason.UNKNOWN
                if (reason == DownloadFailureReason.UNAUTHENTICATED_GUEST) {
                    guestPromptFileTitle.value = file.title
                } else {
                    transientActionErrorReason.value = reason
                }
            }
        }
    }

    fun cancelDownload(file: StudyFileMetadata) {
        val versionId = file.activeVersionId?.takeIf { it.isNotBlank() } ?: return
        val downloadsRepo = offlineDownloadsRepository ?: return
        transientActionErrorReason.value = null

        viewModelScope.launch {
            downloadsRepo.cancelDownload(fileId = file.id, versionId = versionId)
        }
    }

    fun retryDownload(file: StudyFileMetadata) {
        val session = authRepository?.sessionFlow?.value
        if (session == null || !session.canDownloadOfflineFiles) {
            guestPromptFileTitle.value = file.title
            return
        }
        val downloadsRepo = offlineDownloadsRepository ?: return
        transientActionErrorReason.value = null

        viewModelScope.launch {
            val result = downloadsRepo.retryDownload(file = file)
            if (result is AppResult.Error) {
                val reason = (result.cause as? DownloadException)?.reason
                    ?: DownloadFailureReason.UNKNOWN
                if (reason == DownloadFailureReason.UNAUTHENTICATED_GUEST) {
                    guestPromptFileTitle.value = file.title
                } else {
                    transientActionErrorReason.value = reason
                }
            }
        }
    }

    fun dismissGuestDownloadPrompt() {
        guestPromptFileTitle.value = null
    }

    fun dismissTransientError() {
        transientActionErrorReason.value = null
    }

    private fun observeUiState() {
        val filesFlow = catalogSnapshot
            .map { snapshot -> (snapshot as? CatalogSnapshot.Loaded)?.files.orEmpty() }
            .distinctUntilChanged()

        val offlineStatesFlow = filesFlow.flatMapLatest { files ->
            if (files.isEmpty() || offlineDownloadsRepository == null) {
                flowOf(emptyMap())
            } else {
                offlineDownloadsRepository.observeSectionOfflineStates(files)
            }
        }

        val canDownloadFlow = authRepository?.sessionFlow
            ?.map { it.canDownloadOfflineFiles }
            ?: flowOf(false)

        val transientUiFlow = combine(
            guestPromptFileTitle,
            transientActionErrorReason
        ) { guestPrompt, transientError ->
            guestPrompt to transientError
        }

        viewModelScope.launch {
            combine(
                catalogSnapshot,
                offlineStatesFlow,
                canDownloadFlow,
                transientUiFlow,
                badgeExpiryTrigger
            ) { snapshot, offlineStates, canDownload, transientUi, _ ->
                val (guestPrompt, transientError) = transientUi
                when (snapshot) {
                    CatalogSnapshot.Loading -> SectionFilesUiState.Loading
                    CatalogSnapshot.Empty -> SectionFilesUiState.Empty
                    is CatalogSnapshot.Loaded -> {
                        val nowEpochMillis = resolveCurrentNowEpochMillis()
                        val newVersionBadgeFileIds = snapshot.files
                            .filter { file ->
                                NewVersionBadgePolicy.shouldShowNewVersionBadge(
                                    file = file,
                                    offlineState = offlineStates[file.id],
                                    nowEpochMillis = nowEpochMillis
                                )
                            }
                            .mapTo(mutableSetOf()) { it.id }
                        SectionFilesUiState.Content(
                            files = snapshot.files,
                            offlineStates = offlineStates,
                            newVersionBadgeFileIds = newVersionBadgeFileIds,
                            canDownloadOfflineFiles = canDownload,
                            guestDownloadPromptFileTitle = guestPrompt,
                            transientActionErrorReason = transientError
                        )
                    }
                    is CatalogSnapshot.Failed -> SectionFilesUiState.Error(
                        reason = snapshot.reason,
                        offlineSavedFiles = snapshot.offlineSavedFiles
                    )
                }
            }.collect { combinedState ->
                _uiState.value = combinedState
                if (combinedState is SectionFilesUiState.Content) {
                    scheduleNextBadgeExpiry(
                        files = combinedState.files,
                        offlineStates = combinedState.offlineStates,
                        nowEpochMillis = resolveCurrentNowEpochMillis()
                    )
                } else {
                    cancelBadgeExpiryJob()
                }
            }
        }
    }

    private fun scheduleNextBadgeExpiry(
        files: List<StudyFileMetadata>,
        offlineStates: Map<String, StudyFileOfflineState>,
        nowEpochMillis: Long
    ) {
        var nextExpiryEpochMillis: Long? = null
        for (file in files) {
            if (!NewVersionBadgePolicy.shouldShowNewVersionBadge(
                    file = file,
                    offlineState = offlineStates[file.id],
                    nowEpochMillis = nowEpochMillis
                )
            ) {
                continue
            }
            val publishedAt = file.activeVersionPublishedAtEpochMillis ?: continue
            val expiryEpochMillis = publishedAt + NewVersionBadgePolicy.ONE_MONTH_WINDOW_MILLIS
            if (expiryEpochMillis > nowEpochMillis) {
                nextExpiryEpochMillis = if (nextExpiryEpochMillis == null) {
                    expiryEpochMillis
                } else {
                    minOf(nextExpiryEpochMillis, expiryEpochMillis)
                }
            }
        }

        if (nextExpiryEpochMillis == null) {
            cancelBadgeExpiryJob()
            return
        }

        if (scheduledExpiryTargetEpochMillis == nextExpiryEpochMillis && badgeExpiryJob?.isActive == true) {
            return
        }

        badgeExpiryJob?.cancel()
        scheduledExpiryTargetEpochMillis = nextExpiryEpochMillis
        val waitMillis = (nextExpiryEpochMillis - nowEpochMillis).coerceAtLeast(1L)
        badgeExpiryJob = viewModelScope.launch {
            delay(waitMillis)
            scheduledExpiryTargetEpochMillis = null
            badgeExpiryTrigger.value = badgeExpiryTrigger.value + 1L
        }
    }

    private fun resolveCurrentNowEpochMillis(): Long {
        val reportedWall = clockEpochMillis()
        val monoProvider = monotonicElapsedMillis
        if (monoProvider == null) {
            if (reportedWall <= 0L) {
                return anchorWallEpochMillis ?: reportedWall
            }
            val anchorWall = anchorWallEpochMillis
            if (anchorWall == null) {
                anchorWallEpochMillis = reportedWall
                return reportedWall
            }
            return maxOf(reportedWall, anchorWall)
        }

        val currentMono = monoProvider()
        val existingAnchorWall = anchorWallEpochMillis
        val existingAnchorMono = anchorMonotonicMillis
        if (existingAnchorWall == null || existingAnchorMono == null) {
            if (reportedWall > 0L) {
                anchorWallEpochMillis = reportedWall
                anchorMonotonicMillis = currentMono
            }
            return reportedWall
        }

        return NewVersionBadgePolicy.resolveTrustedNowEpochMillis(
            reportedWallEpochMillis = reportedWall,
            anchorWallEpochMillis = existingAnchorWall,
            anchorMonotonicMillis = existingAnchorMono,
            currentMonotonicMillis = currentMono
        )
    }

    private fun cancelBadgeExpiryJob() {
        badgeExpiryJob?.cancel()
        badgeExpiryJob = null
        scheduledExpiryTargetEpochMillis = null
    }

    private fun loadFiles() {
        viewModelScope.launch {
            catalogSnapshot.value = CatalogSnapshot.Loading
            when (val result = catalogRepository.getPublishedStudyFiles(sectionId)) {
                is AppResult.Success -> {
                    catalogSnapshot.value = if (result.data.isEmpty()) {
                        CatalogSnapshot.Empty
                    } else {
                        CatalogSnapshot.Loaded(result.data)
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? CatalogException)?.reason
                        ?: CatalogFailureReason.UNKNOWN
                    val savedOffline = runCatching {
                        offlineDownloadsRepository?.getSavedVersionsForSection(sectionId).orEmpty()
                    }.getOrElse { emptyList() }
                    catalogSnapshot.value = CatalogSnapshot.Failed(
                        reason = reason,
                        offlineSavedFiles = savedOffline
                    )
                }
                AppResult.Loading -> {
                    catalogSnapshot.value = CatalogSnapshot.Loading
                }
            }
        }
    }

    override fun onCleared() {
        cancelBadgeExpiryJob()
        super.onCleared()
    }

    companion object {
        fun provideFactory(
            sectionId: String,
            catalogRepository: CatalogRepository,
            authRepository: AuthRepository? = null,
            offlineDownloadsRepository: OfflineDownloadsRepository? = null,
            clockEpochMillis: () -> Long = { System.currentTimeMillis() },
            monotonicElapsedMillis: (() -> Long)? = { SystemClock.elapsedRealtime() }
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SectionFilesViewModel(
                    sectionId = sectionId,
                    catalogRepository = catalogRepository,
                    authRepository = authRepository,
                    offlineDownloadsRepository = offlineDownloadsRepository,
                    clockEpochMillis = clockEpochMillis,
                    monotonicElapsedMillis = monotonicElapsedMillis
                ) as T
            }
        }
    }
}
