package com.studentlearning.platform2026.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.PreferencesRepository
import com.studentlearning.platform2026.domain.repository.SystemStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    systemStatusRepository: SystemStatusRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private data class AccountActionState(
        val isSigningOut: Boolean = false,
        val signOutErrorReason: AdminFailureReason? = null,
        val signedOutMessageVisible: Boolean = false
    )

    private val firebaseReadiness = systemStatusRepository.getFirebaseReadiness()
    private val accountActionState = MutableStateFlow(AccountActionState())

    init {
        viewModelScope.launch {
            authRepository.refreshAdminSession(forceTokenRefresh = false)
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferencesFlow,
        authRepository.sessionFlow,
        accountActionState
    ) { preferences, adminSession, actionState ->
        SettingsUiState(
            isLoading = false,
            preferences = preferences,
            firebaseReadiness = firebaseReadiness,
            adminSession = adminSession,
            isSigningOut = actionState.isSigningOut,
            signOutErrorReason = actionState.signOutErrorReason,
            signedOutMessageVisible = actionState.signedOutMessageVisible && !adminSession.isSignedIn
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = SettingsUiState(
                isLoading = true,
                firebaseReadiness = firebaseReadiness,
                adminSession = authRepository.sessionFlow.value
            )
        )

    fun onToggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDarkModeEnabled(enabled)
        }
    }

    fun onSignOut() {
        if (accountActionState.value.isSigningOut) return
        viewModelScope.launch {
            accountActionState.update {
                it.copy(
                    isSigningOut = true,
                    signOutErrorReason = null,
                    signedOutMessageVisible = false
                )
            }
            when (val result = authRepository.signOut()) {
                is AppResult.Success -> {
                    // Local device preferences & study state are intentionally NOT cleared.
                    accountActionState.update {
                        it.copy(
                            isSigningOut = false,
                            signOutErrorReason = null,
                            signedOutMessageVisible = true
                        )
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    accountActionState.update {
                        it.copy(
                            isSigningOut = false,
                            signOutErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN,
                            signedOutMessageVisible = false
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun dismissAccountFeedback() {
        accountActionState.update {
            it.copy(signOutErrorReason = null, signedOutMessageVisible = false)
        }
    }

    companion object {
        fun provideFactory(
            preferencesRepository: PreferencesRepository,
            systemStatusRepository: SystemStatusRepository,
            authRepository: AuthRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    preferencesRepository = preferencesRepository,
                    systemStatusRepository = systemStatusRepository,
                    authRepository = authRepository
                ) as T
            }
        }
    }
}
