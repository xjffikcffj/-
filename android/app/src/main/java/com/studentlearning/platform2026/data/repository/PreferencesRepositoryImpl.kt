package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.data.local.PreferencesDataSource
import com.studentlearning.platform2026.domain.model.UserPreferences
import com.studentlearning.platform2026.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow

class PreferencesRepositoryImpl(
    private val dataSource: PreferencesDataSource
) : PreferencesRepository {

    override val preferencesFlow: Flow<UserPreferences> = dataSource.preferencesFlow

    override suspend fun setDarkModeEnabled(enabled: Boolean) {
        dataSource.setDarkModeEnabled(enabled)
    }

    override suspend fun markInitialLaunchCompleted() {
        dataSource.markInitialLaunchCompleted()
    }
}
