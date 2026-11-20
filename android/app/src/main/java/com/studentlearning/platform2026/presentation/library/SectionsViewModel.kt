package com.studentlearning.platform2026.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SectionsViewModel(
    private val subjectId: String,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SectionsUiState>(SectionsUiState.Loading)
    val uiState: StateFlow<SectionsUiState> = _uiState.asStateFlow()

    init {
        loadSections()
    }

    fun retry() {
        loadSections()
    }

    private fun loadSections() {
        viewModelScope.launch {
            _uiState.value = SectionsUiState.Loading
            when (val result = catalogRepository.getPublishedSections(subjectId)) {
                is AppResult.Success -> {
                    _uiState.value = if (result.data.isEmpty()) {
                        SectionsUiState.Empty
                    } else {
                        SectionsUiState.Content(result.data)
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? CatalogException)?.reason
                        ?: CatalogFailureReason.UNKNOWN
                    _uiState.value = SectionsUiState.Error(reason)
                }
                AppResult.Loading -> {
                    _uiState.value = SectionsUiState.Loading
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            subjectId: String,
            catalogRepository: CatalogRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SectionsViewModel(
                    subjectId = subjectId,
                    catalogRepository = catalogRepository
                ) as T
            }
        }
    }
}
