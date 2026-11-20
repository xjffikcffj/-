package com.studentlearning.platform2026.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studentlearning.platform2026.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.haqibatiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "haqibati_preferences"
)

class PreferencesDataSource(private val context: Context) {

    private object Keys {
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        val DARK_MODE_ENABLED = booleanPreferencesKey("dark_mode_enabled")
        val HAS_COMPLETED_INITIAL_LAUNCH = booleanPreferencesKey("has_completed_initial_launch")
    }

    val preferencesFlow: Flow<UserPreferences> = context.haqibatiDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserPreferences(
                preferredLanguage = prefs[Keys.PREFERRED_LANGUAGE] ?: UserPreferences.DEFAULT_LANGUAGE,
                darkModeEnabled = prefs[Keys.DARK_MODE_ENABLED] ?: false,
                hasCompletedInitialLaunch = prefs[Keys.HAS_COMPLETED_INITIAL_LAUNCH] ?: false
            )
        }

    suspend fun setDarkModeEnabled(enabled: Boolean) {
        context.haqibatiDataStore.edit { prefs ->
            prefs[Keys.DARK_MODE_ENABLED] = enabled
        }
    }

    suspend fun markInitialLaunchCompleted() {
        context.haqibatiDataStore.edit { prefs ->
            prefs[Keys.HAS_COMPLETED_INITIAL_LAUNCH] = true
        }
    }
}
