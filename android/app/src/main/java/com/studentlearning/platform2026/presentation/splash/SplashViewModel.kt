package com.studentlearning.platform2026.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DefaultDispatcherProvider
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.domain.model.AppBootstrapState
import com.studentlearning.platform2026.domain.repository.PreferencesRepository
import com.studentlearning.platform2026.domain.repository.SystemStatusRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SplashUiState(
    val bootstrapResult: AppResult<AppBootstrapState> = AppResult.Loading,
    val isReadyToNavigate: Boolean = false
)

class SplashViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val systemStatusRepository: SystemStatusRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val minimumDisplayDurationMs: Long = DEFAULT_SPLASH_DURATION_MS
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        initializeApp()
    }

    fun retry() {
        _uiState.value = SplashUiState(
            bootstrapResult = AppResult.Loading,
            isReadyToNavigate = false
        )
        initializeApp()
    }

    private fun initializeApp() {
        viewModelScope.launch {
            val bootstrapResult = withContext(dispatcherProvider.io) {
                runCatching {
                    val preferences = preferencesRepository.preferencesFlow.first()
                    if (!preferences.hasCompletedInitialLaunch) {
                        preferencesRepository.markInitialLaunchCompleted()
                    }
                    val readiness = systemStatusRepository.getFirebaseReadiness()
                    AppBootstrapState(
                        preferences = preferences.copy(hasCompletedInitialLaunch = true),
                        firebaseReadiness = readiness
                    )
                }.fold(
                    onSuccess = { AppResult.Success(it) },
                    onFailure = { AppResult.Error(it.message.orEmpty(), it) }
                )
            }

            if (minimumDisplayDurationMs > 0L) {
                delay(minimumDisplayDurationMs)
            }

            _uiState.value = SplashUiState(
                bootstrapResult = bootstrapResult,
                isReadyToNavigate = bootstrapResult is AppResult.Success
            )
        }
    }

    companion object {
        const val DEFAULT_SPLASH_DURATION_MS = 1200L

        fun provideFactory(
            preferencesRepository: PreferencesRepository,
            systemStatusRepository: SystemStatusRepository,
            dispatcherProvider: DispatcherProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SplashViewModel(
                    preferencesRepository = preferencesRepository,
                    systemStatusRepository = systemStatusRepository,
                    dispatcherProvider = dispatcherProvider
                ) as T
            }
        }
    }
}
