package com.studentlearning.platform2026.domain.model

data class UserPreferences(
    val preferredLanguage: String = DEFAULT_LANGUAGE,
    val darkModeEnabled: Boolean = false,
    val hasCompletedInitialLaunch: Boolean = false
) {
    companion object {
        const val DEFAULT_LANGUAGE = "ar"
    }
}
