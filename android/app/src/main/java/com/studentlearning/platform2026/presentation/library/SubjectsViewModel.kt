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

class SubjectsViewModel(
    private val branchId: String,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SubjectsUiState>(SubjectsUiState.Loading)
    val uiState: StateFlow<SubjectsUiState> = _uiState.asStateFlow()

    init {
        loadSubjects()
    }

    fun retry() {
        loadSubjects()
    }

    private fun loadSubjects() {
        viewModelScope.launch {
            _uiState.value = SubjectsUiState.Loading
            when (val result = catalogRepository.getPublishedSubjects(branchId)) {
                is AppResult.Success -> {
                    _uiState.value = if (result.data.isEmpty()) {
                        SubjectsUiState.Empty
                    } else {
                        SubjectsUiState.Content(result.data)
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? CatalogException)?.reason
                        ?: CatalogFailureReason.UNKNOWN
                    _uiState.value = SubjectsUiState.Error(reason)
                }
                AppResult.Loading -> {
                    _uiState.value = SubjectsUiState.Loading
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            branchId: String,
            catalogRepository: CatalogRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SubjectsViewModel(
                    branchId = branchId,
                    catalogRepository = catalogRepository
                ) as T
            }
        }
    }
}
