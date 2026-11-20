package com.studentlearning.platform2026.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AccountViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState(session = authRepository.sessionFlow.value))
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.sessionFlow.collect { session ->
                _uiState.update { state ->
                    state.copy(
                        session = session,
                        signUpSucceeded = if (session.isSignedIn) state.signUpSucceeded else false
                    )
                }
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (_uiState.value.isSubmittingSignIn) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmittingSignIn = true,
                    signInErrorReason = null,
                    signInValidationError = null,
                    accountStatusMessage = null
                )
            }
            when (val result = authRepository.signInWithEmailAndPassword(email, password)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingSignIn = false,
                            session = result.data,
                            signInErrorReason = null,
                            signInValidationError = null
                        )
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    _uiState.update {
                        it.copy(
                            isSubmittingSignIn = false,
                            signInErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN,
                            signInValidationError = ex?.validationError
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun createStudentAccount(email: String, password: String, confirmPassword: String) {
        if (_uiState.value.isSubmittingSignUp) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmittingSignUp = true,
                    signUpErrorReason = null,
                    signUpValidationError = null,
                    signUpSucceeded = false,
                    accountStatusMessage = null
                )
            }
            when (val result = authRepository.createStudentAccount(email, password, confirmPassword)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingSignUp = false,
                            session = result.data,
                            signUpErrorReason = null,
                            signUpValidationError = null,
                            signUpSucceeded = true
                        )
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    _uiState.update {
                        it.copy(
                            isSubmittingSignUp = false,
                            signUpErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN,
                            signUpValidationError = ex?.validationError,
                            signUpSucceeded = false
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun sendPasswordResetEmail(email: String) {
        if (_uiState.value.isSubmittingPasswordReset) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmittingPasswordReset = true,
                    passwordResetErrorReason = null,
                    passwordResetValidationError = null,
                    passwordResetEmailSentTo = null
                )
            }
            when (val result = authRepository.sendPasswordResetEmail(email)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingPasswordReset = false,
                            passwordResetErrorReason = null,
                            passwordResetValidationError = null,
                            passwordResetEmailSentTo = email.trim()
                        )
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    _uiState.update {
                        it.copy(
                            isSubmittingPasswordReset = false,
                            passwordResetErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN,
                            passwordResetValidationError = ex?.validationError,
                            passwordResetEmailSentTo = null
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun signOut() {
        if (_uiState.value.isSigningOut) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSigningOut = true,
                    accountActionErrorReason = null,
                    accountStatusMessage = null
                )
            }
            when (val result = authRepository.signOut()) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSigningOut = false,
                            signUpSucceeded = false,
                            accountStatusMessage = AccountStatusMessage.SIGNED_OUT
                        )
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    _uiState.update {
                        it.copy(
                            isSigningOut = false,
                            accountActionErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun refreshSession() {
        if (_uiState.value.isRefreshingSession) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshingSession = true,
                    accountActionErrorReason = null,
                    accountStatusMessage = null
                )
            }
            when (val result = authRepository.refreshAdminSession(forceTokenRefresh = true)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isRefreshingSession = false,
                            session = result.data,
                            accountStatusMessage = AccountStatusMessage.SESSION_REFRESHED
                        )
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    _uiState.update {
                        it.copy(
                            isRefreshingSession = false,
                            accountActionErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun clearSignInFeedback() {
        _uiState.update {
            it.copy(signInErrorReason = null, signInValidationError = null)
        }
    }

    fun clearSignUpFeedback() {
        _uiState.update {
            it.copy(signUpErrorReason = null, signUpValidationError = null)
        }
    }

    fun clearPasswordResetFeedback() {
        _uiState.update {
            it.copy(
                passwordResetErrorReason = null,
                passwordResetValidationError = null,
                passwordResetEmailSentTo = null
            )
        }
    }

    fun clearAccountActionFeedback() {
        _uiState.update {
            it.copy(accountActionErrorReason = null, accountStatusMessage = null)
        }
    }

    companion object {
        fun provideFactory(
            authRepository: AuthRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AccountViewModel(authRepository = authRepository) as T
            }
        }
    }
}
