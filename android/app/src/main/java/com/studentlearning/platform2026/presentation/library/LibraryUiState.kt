package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Content(val branches: List<StudyBranch>) : LibraryUiState
    data object Empty : LibraryUiState
    data class Error(val reason: CatalogFailureReason) : LibraryUiState
}
