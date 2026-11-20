package com.studentlearning.platform2026.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.BuildConfig
import com.studentlearning.platform2026.domain.repository.PreferencesRepository
import com.studentlearning.platform2026.domain.repository.SystemStatusRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val preferencesRepository: PreferencesRepository,
    systemStatusRepository: SystemStatusRepository,
    applicationPackageId: String = BuildConfig.APPLICATION_ID
) : ViewModel() {

    private val firebaseReadiness = systemStatusRepository.getFirebaseReadiness()

    val uiState: StateFlow<HomeUiState> = preferencesRepository.preferencesFlow
        .map { preferences ->
            HomeUiState(
                isLoading = false,
                preferences = preferences,
                firebaseReadiness = firebaseReadiness,
                applicationPackageId = applicationPackageId
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = HomeUiState(
                isLoading = true,
                firebaseReadiness = firebaseReadiness,
                applicationPackageId = applicationPackageId
            )
        )

    fun onToggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDarkModeEnabled(enabled)
        }
    }

    companion object {
        fun provideFactory(
            preferencesRepository: PreferencesRepository,
            systemStatusRepository: SystemStatusRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    preferencesRepository = preferencesRepository,
                    systemStatusRepository = systemStatusRepository
                ) as T
            }
        }
    }
}
