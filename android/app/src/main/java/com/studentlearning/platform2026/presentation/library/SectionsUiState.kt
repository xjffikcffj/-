package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudySection

sealed interface SectionsUiState {
    data object Loading : SectionsUiState
    data class Content(val sections: List<StudySection>) : SectionsUiState
    data object Empty : SectionsUiState
    data class Error(val reason: CatalogFailureReason) : SectionsUiState
}
