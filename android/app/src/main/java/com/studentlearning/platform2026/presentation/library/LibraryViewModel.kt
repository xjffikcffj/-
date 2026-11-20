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

class LibraryViewModel(
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        loadBranches()
    }

    fun retry() {
        loadBranches()
    }

    private fun loadBranches() {
        viewModelScope.launch {
            _uiState.value = LibraryUiState.Loading
            when (val result = catalogRepository.getPublishedBranches()) {
                is AppResult.Success -> {
                    _uiState.value = if (result.data.isEmpty()) {
                        LibraryUiState.Empty
                    } else {
                        LibraryUiState.Content(result.data)
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? CatalogException)?.reason
                        ?: CatalogFailureReason.UNKNOWN
                    _uiState.value = LibraryUiState.Error(reason)
                }
                AppResult.Loading -> {
                    _uiState.value = LibraryUiState.Loading
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            catalogRepository: CatalogRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LibraryViewModel(catalogRepository = catalogRepository) as T
            }
        }
    }
}
