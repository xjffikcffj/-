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

class SectionFilesViewModel(
    private val sectionId: String,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SectionFilesUiState>(SectionFilesUiState.Loading)
    val uiState: StateFlow<SectionFilesUiState> = _uiState.asStateFlow()

    init {
        loadFiles()
    }

    fun retry() {
        loadFiles()
    }

    private fun loadFiles() {
        viewModelScope.launch {
            _uiState.value = SectionFilesUiState.Loading
            when (val result = catalogRepository.getPublishedStudyFiles(sectionId)) {
                is AppResult.Success -> {
                    _uiState.value = if (result.data.isEmpty()) {
                        SectionFilesUiState.Empty
                    } else {
                        SectionFilesUiState.Content(result.data)
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? CatalogException)?.reason
                        ?: CatalogFailureReason.UNKNOWN
                    _uiState.value = SectionFilesUiState.Error(reason)
                }
                AppResult.Loading -> {
                    _uiState.value = SectionFilesUiState.Loading
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            sectionId: String,
            catalogRepository: CatalogRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SectionFilesViewModel(
                    sectionId = sectionId,
                    catalogRepository = catalogRepository
                ) as T
            }
        }
    }
}
