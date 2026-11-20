package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyFileMetadata

sealed interface SectionFilesUiState {
    data object Loading : SectionFilesUiState
    data class Content(val files: List<StudyFileMetadata>) : SectionFilesUiState
    data object Empty : SectionFilesUiState
    data class Error(val reason: CatalogFailureReason) : SectionFilesUiState
}
