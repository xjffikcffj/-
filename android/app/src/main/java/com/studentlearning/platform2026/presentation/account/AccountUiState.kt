package com.studentlearning.platform2026.presentation.account

import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError

enum class AccountStatusMessage {
    SIGNED_OUT,
    SESSION_REFRESHED
}

data class AccountUiState(
    val session: AdminSession = AdminSession(),
    val isSubmittingSignIn: Boolean = false,
    val isSubmittingSignUp: Boolean = false,
    val isSubmittingPasswordReset: Boolean = false,
    val isSigningOut: Boolean = false,
    val isRefreshingSession: Boolean = false,
    val signInErrorReason: AdminFailureReason? = null,
    val signInValidationError: AdminValidationError? = null,
    val signUpErrorReason: AdminFailureReason? = null,
    val signUpValidationError: AdminValidationError? = null,
    val signUpSucceeded: Boolean = false,
    val passwordResetErrorReason: AdminFailureReason? = null,
    val passwordResetValidationError: AdminValidationError? = null,
    val passwordResetEmailSentTo: String? = null,
    val accountActionErrorReason: AdminFailureReason? = null,
    val accountStatusMessage: AccountStatusMessage? = null
)
