package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val preferencesFlow: Flow<UserPreferences>
    suspend fun setDarkModeEnabled(enabled: Boolean)
    suspend fun markInitialLaunchCompleted()
}
