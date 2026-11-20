package com.studentlearning.platform2026.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DownloadsViewModel(
    authRepository: AuthRepository? = null
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = if (authRepository == null) {
        MutableStateFlow(DownloadsUiState(userRole = AppUserRole.GUEST)).asStateFlow()
    } else {
        authRepository.sessionFlow
            .map { session ->
                DownloadsUiState(
                    isInitialized = true,
                    hasSavedFiles = false,
                    userRole = session.userRole
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = DownloadsUiState(
                    isInitialized = true,
                    hasSavedFiles = false,
                    userRole = authRepository.sessionFlow.value.userRole
                )
            )
    }

    companion object {
        fun provideFactory(
            authRepository: AuthRepository? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DownloadsViewModel(authRepository = authRepository) as T
            }
        }
    }
}
