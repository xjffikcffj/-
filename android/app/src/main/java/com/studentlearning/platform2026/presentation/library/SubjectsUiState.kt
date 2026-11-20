package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudySubject

sealed interface SubjectsUiState {
    data object Loading : SubjectsUiState
    data class Content(val subjects: List<StudySubject>) : SubjectsUiState
    data object Empty : SubjectsUiState
    data class Error(val reason: CatalogFailureReason) : SubjectsUiState
}
