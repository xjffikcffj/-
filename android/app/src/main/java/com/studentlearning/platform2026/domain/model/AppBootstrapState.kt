package com.studentlearning.platform2026.domain.model

data class AppBootstrapState(
    val preferences: UserPreferences,
    val firebaseReadiness: FirebaseReadiness
)
