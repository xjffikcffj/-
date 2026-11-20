package com.studentlearning.platform2026.presentation.settings

import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.FirebaseReadiness
import com.studentlearning.platform2026.domain.model.UserPreferences

data class SettingsUiState(
    val isLoading: Boolean = true,
    val preferences: UserPreferences = UserPreferences(),
    val firebaseReadiness: FirebaseReadiness? = null,
    val adminSession: AdminSession = AdminSession(),
    val isSigningOut: Boolean = false,
    val signOutErrorReason: AdminFailureReason? = null,
    val signedOutMessageVisible: Boolean = false
)
