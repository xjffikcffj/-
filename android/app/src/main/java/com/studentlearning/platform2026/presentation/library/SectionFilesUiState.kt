package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState

sealed interface SectionFilesUiState {
    data object Loading : SectionFilesUiState

    data class Content(
        val files: List<StudyFileMetadata>,
        val totalFilesCount: Int = files.size,
        val offlineStates: Map<String, StudyFileOfflineState> = emptyMap(),
        val newVersionBadgeFileIds: Set<String> = emptySet(),
        val favoriteFileIds: Set<String> = emptySet(),
        val showFavoritesOnly: Boolean = false,
        val isReorderMode: Boolean = false,
        val hasCustomOrder: Boolean = false,
        val canDownloadOfflineFiles: Boolean = false,
        val guestDownloadPromptFileTitle: String? = null,
        val transientActionErrorReason: DownloadFailureReason? = null
    ) : SectionFilesUiState

    data object Empty : SectionFilesUiState

    data class Error(
        val reason: CatalogFailureReason,
        val offlineSavedFiles: List<LocalDownloadedFileVersion> = emptyList()
    ) : SectionFilesUiState
}
