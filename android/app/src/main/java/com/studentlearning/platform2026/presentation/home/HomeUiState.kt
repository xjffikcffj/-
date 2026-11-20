package com.studentlearning.platform2026.presentation.home

import com.studentlearning.platform2026.domain.model.FirebaseReadiness
import com.studentlearning.platform2026.domain.model.UserPreferences

data class HomeUiState(
    val isLoading: Boolean = true,
    val preferences: UserPreferences = UserPreferences(),
    val firebaseReadiness: FirebaseReadiness? = null,
    val applicationPackageId: String = ""
)
