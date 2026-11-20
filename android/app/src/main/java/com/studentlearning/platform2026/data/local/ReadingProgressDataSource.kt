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
 * Device-local DataStore persistence for per-(fileId, versionId) reading progress.
 *
 * Keys are derived exclusively from the stable ([fileId], [versionId]) pair (hex-encoded to
 * prevent separator collisions) and NEVER include Firebase Auth UIDs, user emails, or mutable
 * file titles. Signing out or switching accounts never reads, mutates, or clears this DataStore,
 * and publishing or opening v2 never overwrites v1's saved reading page.
 */
class ReadingProgressDataSource(
    private val dataStore: DataStore<Preferences>,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() }
) {
    constructor(context: Context) : this(dataStore = context.haqibatiReadingProgressDataStore)

    fun observeProgress(fileId: String, versionId: String? = null): Flow<FileReadingProgress?> {
        val normalizedFileId = fileId.trim()
        if (normalizedFileId.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(null)
        }
        val normalizedVersionId = versionId?.trim()?.takeIf { it.isNotEmpty() }
        val encodedKey = encodeVersionScopeKey(normalizedFileId, normalizedVersionId)
        val lastPageKey = intPreferencesKey("${PREFIX_LAST_PAGE}$encodedKey")
        val totalPagesKey = intPreferencesKey("${PREFIX_TOTAL_PAGES}$encodedKey")
        val updatedAtKey = longPreferencesKey("${PREFIX_UPDATED_AT}$encodedKey")

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
                    fileId = normalizedFileId,
                    lastPageNumber = savedPage,
                    totalPages = (prefs[totalPagesKey] ?: 0).coerceAtLeast(0),
                    updatedAtEpochMillis = prefs[updatedAtKey] ?: 0L,
                    versionId = normalizedVersionId
                )
            }
    }

    suspend fun getProgress(fileId: String, versionId: String? = null): FileReadingProgress? {
        return observeProgress(fileId = fileId, versionId = versionId).firstOrNull()
    }

    suspend fun saveLastPage(
        fileId: String,
        pageNumber: Int,
        totalPages: Int,
        versionId: String? = null
    ) {
        val normalizedFileId = fileId.trim()
        if (normalizedFileId.isEmpty() || totalPages <= 0) return
        val normalizedVersionId = versionId?.trim()?.takeIf { it.isNotEmpty() }
        val safePage = pageNumber.coerceIn(1, totalPages)
        val encodedKey = encodeVersionScopeKey(normalizedFileId, normalizedVersionId)
        val lastPageKey = intPreferencesKey("${PREFIX_LAST_PAGE}$encodedKey")
        val totalPagesKey = intPreferencesKey("${PREFIX_TOTAL_PAGES}$encodedKey")
        val updatedAtKey = longPreferencesKey("${PREFIX_UPDATED_AT}$encodedKey")

        dataStore.edit { prefs ->
            prefs[lastPageKey] = safePage
            prefs[totalPagesKey] = totalPages
            prefs[updatedAtKey] = clockEpochMillis()
        }
    }

    suspend fun clearProgress(fileId: String, versionId: String? = null) {
        val normalizedFileId = fileId.trim()
        if (normalizedFileId.isEmpty()) return
        val normalizedVersionId = versionId?.trim()?.takeIf { it.isNotEmpty() }
        val encodedKey = encodeVersionScopeKey(normalizedFileId, normalizedVersionId)
        val lastPageKey = intPreferencesKey("${PREFIX_LAST_PAGE}$encodedKey")
        val totalPagesKey = intPreferencesKey("${PREFIX_TOTAL_PAGES}$encodedKey")
        val updatedAtKey = longPreferencesKey("${PREFIX_UPDATED_AT}$encodedKey")

        dataStore.edit { prefs ->
            prefs.remove(lastPageKey)
            prefs.remove(totalPagesKey)
            prefs.remove(updatedAtKey)
        }
    }

    private fun encodeVersionScopeKey(fileId: String, versionId: String?): String {
        val encodedFile = encodeHex(fileId)
        return if (versionId == null) {
            encodedFile
        } else {
            "${encodedFile}_v_${encodeHex(versionId)}"
        }
    }

    private fun encodeHex(value: String): String {
        return value.toByteArray(Charsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PREFIX_LAST_PAGE = "file_last_page_"
        const val PREFIX_TOTAL_PAGES = "file_total_pages_"
        const val PREFIX_UPDATED_AT = "file_updated_at_"
    }
}
