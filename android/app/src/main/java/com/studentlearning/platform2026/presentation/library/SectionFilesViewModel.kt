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
import com.studentlearning.platform2026.domain.model.LocalFileOrderingHelper
import com.studentlearning.platform2026.domain.model.NewVersionBadgePolicy
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import com.studentlearning.platform2026.domain.repository.LocalFileOrganizationRepository
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
    private val localFileOrganizationRepository: LocalFileOrganizationRepository? = null,
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

    private data class OrganizationUiControl(
        val favoriteIds: Set<String>,
        val savedCustomOrderIds: List<String>,
        val showFavoritesOnly: Boolean,
        val isReorderMode: Boolean,
        val draftOrderedFileIds: List<String>
    )

    private val sectionScopeKey = LocalFileOrganizationRepository.sectionScope(sectionId)

    private val catalogSnapshot = MutableStateFlow<CatalogSnapshot>(CatalogSnapshot.Loading)
    private val guestPromptFileTitle = MutableStateFlow<String?>(null)
    private val transientActionErrorReason = MutableStateFlow<DownloadFailureReason?>(null)
    private val badgeExpiryTrigger = MutableStateFlow(0L)
    private val showFavoritesOnlyFlow = MutableStateFlow(false)
    private val isReorderModeFlow = MutableStateFlow(false)
    private val draftOrderedFileIdsFlow = MutableStateFlow<List<String>>(emptyList())

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

    fun toggleFavorite(file: StudyFileMetadata) {
        val orgRepo = localFileOrganizationRepository ?: return
        viewModelScope.launch {
            orgRepo.toggleFavorite(file)
        }
    }

    fun toggleFavoritesFilter() {
        if (isReorderModeFlow.value) return
        showFavoritesOnlyFlow.value = !showFavoritesOnlyFlow.value
    }

    fun enterReorderMode() {
        val loaded = (catalogSnapshot.value as? CatalogSnapshot.Loaded)?.files.orEmpty()
        if (loaded.size <= 1 || localFileOrganizationRepository == null) return
        val currentContent = _uiState.value as? SectionFilesUiState.Content
        val initialOrderedFiles = if (currentContent != null && !currentContent.showFavoritesOnly) {
            currentContent.files
        } else {
            loaded
        }
        draftOrderedFileIdsFlow.value = initialOrderedFiles.map { it.id }
        isReorderModeFlow.value = true
    }

    fun moveFileInDraftOrder(fileId: String, moveUp: Boolean) {
        if (!isReorderModeFlow.value) return
        draftOrderedFileIdsFlow.value = LocalFileOrderingHelper.moveItemInOrderedIds(
            orderedIds = draftOrderedFileIdsFlow.value,
            targetId = fileId,
            moveUp = moveUp
        )
    }

    fun saveReorder() {
        val orgRepo = localFileOrganizationRepository ?: return
        if (!isReorderModeFlow.value) return
        val orderedIds = draftOrderedFileIdsFlow.value
        viewModelScope.launch {
            orgRepo.saveCustomOrder(scopeKey = sectionScopeKey, orderedFileIds = orderedIds)
            isReorderModeFlow.value = false
            draftOrderedFileIdsFlow.value = emptyList()
        }
    }

    fun cancelReorder() {
        isReorderModeFlow.value = false
        draftOrderedFileIdsFlow.value = emptyList()
    }

    fun resetCustomOrder() {
        val orgRepo = localFileOrganizationRepository ?: return
        viewModelScope.launch {
            orgRepo.clearCustomOrder(scopeKey = sectionScopeKey)
            isReorderModeFlow.value = false
            draftOrderedFileIdsFlow.value = emptyList()
        }
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
            transientActionErrorReason,
            badgeExpiryTrigger
        ) { guestPrompt, transientError, expiryTick ->
            Triple(guestPrompt, transientError, expiryTick)
        }

        val favoriteIdsFlow = localFileOrganizationRepository?.observeFavoriteIds()
            ?: flowOf(emptySet())
        val savedCustomOrderFlow = localFileOrganizationRepository?.observeCustomOrder(sectionScopeKey)
            ?: flowOf(emptyList())

        val orgControlFlow = combine(
            favoriteIdsFlow,
            savedCustomOrderFlow,
            showFavoritesOnlyFlow,
            isReorderModeFlow,
            draftOrderedFileIdsFlow
        ) { favIds, savedOrder, favoritesOnly, reorderMode, draftIds ->
            OrganizationUiControl(
                favoriteIds = favIds,
                savedCustomOrderIds = savedOrder,
                showFavoritesOnly = favoritesOnly,
                isReorderMode = reorderMode,
                draftOrderedFileIds = draftIds
            )
        }

        viewModelScope.launch {
            combine(
                catalogSnapshot,
                offlineStatesFlow,
                canDownloadFlow,
                transientUiFlow,
                orgControlFlow
            ) { snapshot, offlineStates, canDownload, transientUi, orgControl ->
                val (guestPrompt, transientError, _) = transientUi
                when (snapshot) {
                    CatalogSnapshot.Loading -> SectionFilesUiState.Loading
                    CatalogSnapshot.Empty -> SectionFilesUiState.Empty
                    is CatalogSnapshot.Loaded -> {
                        val activeOrderIds = if (orgControl.isReorderMode) {
                            orgControl.draftOrderedFileIds
                        } else {
                            orgControl.savedCustomOrderIds
                        }
                        val orderedFiles = LocalFileOrderingHelper.applyLocalFileOrder(
                            items = snapshot.files,
                            idSelector = { it.id },
                            orderedIds = activeOrderIds
                        )
                        val visibleFiles = if (!orgControl.isReorderMode && orgControl.showFavoritesOnly) {
                            orderedFiles.filter { it.id in orgControl.favoriteIds }
                        } else {
                            orderedFiles
                        }
                        val nowEpochMillis = resolveCurrentNowEpochMillis()
                        val newVersionBadgeFileIds = orderedFiles
                            .filter { file ->
                                NewVersionBadgePolicy.shouldShowNewVersionBadge(
                                    file = file,
                                    offlineState = offlineStates[file.id],
                                    nowEpochMillis = nowEpochMillis
                                )
                            }
                            .mapTo(mutableSetOf()) { it.id }
                        SectionFilesUiState.Content(
                            files = visibleFiles,
                            totalFilesCount = snapshot.files.size,
                            offlineStates = offlineStates,
                            newVersionBadgeFileIds = newVersionBadgeFileIds,
                            favoriteFileIds = orgControl.favoriteIds,
                            showFavoritesOnly = orgControl.showFavoritesOnly,
                            isReorderMode = orgControl.isReorderMode,
                            hasCustomOrder = orgControl.savedCustomOrderIds.isNotEmpty(),
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
            localFileOrganizationRepository: LocalFileOrganizationRepository? = null,
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
                    localFileOrganizationRepository = localFileOrganizationRepository,
                    clockEpochMillis = clockEpochMillis,
                    monotonicElapsedMillis = monotonicElapsedMillis
                ) as T
            }
        }
    }
}
