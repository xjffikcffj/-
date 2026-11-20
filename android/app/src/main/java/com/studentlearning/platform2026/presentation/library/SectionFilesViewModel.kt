package com.studentlearning.platform2026.presentation.library

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
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import com.studentlearning.platform2026.domain.repository.OfflineDownloadsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() }
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

    private val _uiState = MutableStateFlow<SectionFilesUiState>(SectionFilesUiState.Loading)
    val uiState: StateFlow<SectionFilesUiState> = _uiState.asStateFlow()

    init {
        observeUiState()
        loadFiles()
    }

    fun retry() {
        loadFiles()
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

        viewModelScope.launch {
            combine(
                catalogSnapshot,
                offlineStatesFlow,
                canDownloadFlow,
                guestPromptFileTitle,
                transientActionErrorReason
            ) { snapshot, offlineStates, canDownload, guestPrompt, transientError ->
                when (snapshot) {
                    CatalogSnapshot.Loading -> SectionFilesUiState.Loading
                    CatalogSnapshot.Empty -> SectionFilesUiState.Empty
                    is CatalogSnapshot.Loaded -> {
                        val nowEpochMillis = clockEpochMillis()
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
            }
        }
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

    companion object {
        fun provideFactory(
            sectionId: String,
            catalogRepository: CatalogRepository,
            authRepository: AuthRepository? = null,
            offlineDownloadsRepository: OfflineDownloadsRepository? = null,
            clockEpochMillis: () -> Long = { System.currentTimeMillis() }
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SectionFilesViewModel(
                    sectionId = sectionId,
                    catalogRepository = catalogRepository,
                    authRepository = authRepository,
                    offlineDownloadsRepository = offlineDownloadsRepository,
                    clockEpochMillis = clockEpochMillis
                ) as T
            }
        }
    }
}
