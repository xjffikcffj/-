package com.studentlearning.platform2026.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studentlearning.platform2026.domain.model.FileReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.haqibatiReadingProgressDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "haqibati_reading_progress"
)

/**
 * Device-local DataStore persistence for per-file reading progress.
 *
 * Keys are derived exclusively from the stable [fileId] (hex-encoded to prevent separator collisions)
 * and NEVER include Firebase Auth UIDs, user emails, or mutable file titles. Signing out or
 * switching accounts never reads, mutates, or clears this DataStore.
 */
class ReadingProgressDataSource(
    private val dataStore: DataStore<Preferences>,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() }
) {
    constructor(context: Context) : this(dataStore = context.haqibatiReadingProgressDataStore)

    fun observeProgress(fileId: String): Flow<FileReadingProgress?> {
        if (fileId.isBlank()) {
            return kotlinx.coroutines.flow.flowOf(null)
        }
        val encodedId = encodeStableFileKey(fileId)
        val lastPageKey = intPreferencesKey("${PREFIX_LAST_PAGE}$encodedId")
        val totalPagesKey = intPreferencesKey("${PREFIX_TOTAL_PAGES}$encodedId")
        val updatedAtKey = longPreferencesKey("${PREFIX_UPDATED_AT}$encodedId")

        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs ->
                val savedPage = prefs[lastPageKey] ?: return@map null
                if (savedPage < 1) return@map null
                FileReadingProgress(
                    fileId = fileId,
                    lastPageNumber = savedPage,
                    totalPages = (prefs[totalPagesKey] ?: 0).coerceAtLeast(0),
                    updatedAtEpochMillis = prefs[updatedAtKey] ?: 0L
                )
            }
    }

    suspend fun getProgress(fileId: String): FileReadingProgress? {
        return observeProgress(fileId).firstOrNull()
    }

    suspend fun saveLastPage(fileId: String, pageNumber: Int, totalPages: Int) {
        if (fileId.isBlank() || totalPages <= 0) return
        val safePage = pageNumber.coerceIn(1, totalPages)
        val encodedId = encodeStableFileKey(fileId)
        val lastPageKey = intPreferencesKey("${PREFIX_LAST_PAGE}$encodedId")
        val totalPagesKey = intPreferencesKey("${PREFIX_TOTAL_PAGES}$encodedId")
        val updatedAtKey = longPreferencesKey("${PREFIX_UPDATED_AT}$encodedId")

        dataStore.edit { prefs ->
            prefs[lastPageKey] = safePage
            prefs[totalPagesKey] = totalPages
            prefs[updatedAtKey] = clockEpochMillis()
        }
    }

    suspend fun clearProgress(fileId: String) {
        if (fileId.isBlank()) return
        val encodedId = encodeStableFileKey(fileId)
        val lastPageKey = intPreferencesKey("${PREFIX_LAST_PAGE}$encodedId")
        val totalPagesKey = intPreferencesKey("${PREFIX_TOTAL_PAGES}$encodedId")
        val updatedAtKey = longPreferencesKey("${PREFIX_UPDATED_AT}$encodedId")

        dataStore.edit { prefs ->
            prefs.remove(lastPageKey)
            prefs.remove(totalPagesKey)
            prefs.remove(updatedAtKey)
        }
    }

    private fun encodeStableFileKey(fileId: String): String {
        return fileId.trim()
            .toByteArray(Charsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PREFIX_LAST_PAGE = "file_last_page_"
        const val PREFIX_TOTAL_PAGES = "file_total_pages_"
        const val PREFIX_UPDATED_AT = "file_updated_at_"
    }
}
