package com.studentlearning.platform2026.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studentlearning.platform2026.domain.model.FavoriteStudyFile
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.haqibatiFileOrganizationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "haqibati_local_file_organization"
)

/**
 * Strictly device-local persistence for student Favorites ("المفضلة المحلية") and
 * per-device custom file ordering ("ترتيب ملفاتي").
 *
 * Security & privacy guarantees:
 * - Never stores Firebase Auth UIDs or user emails.
 * - Never syncs to Firestore or alters public catalog order for other students.
 * - Signing out or switching accounts preserves local favorites and local custom order.
 */
class LocalFileOrganizationDataSource(
    private val dataStore: DataStore<Preferences>,
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() }
) {
    constructor(context: Context) : this(dataStore = context.haqibatiFileOrganizationDataStore)

    fun observeFavorites(): Flow<List<FavoriteStudyFile>> {
        return safePrefsFlow().map { prefs -> readFavorites(prefs) }
    }

    fun observeFavoriteIds(): Flow<Set<String>> {
        return observeFavorites().map { favorites -> favorites.mapTo(linkedSetOf()) { it.fileId } }
    }

    suspend fun addFavorite(file: StudyFileMetadata) {
        val cleanFileId = file.id.trim()
        if (cleanFileId.isEmpty()) return
        val entryKey = encodeHex(cleanFileId)
        val savedAt = clockEpochMillis()

        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_FAVORITE_ENTRY_SET].orEmpty()
            prefs[KEY_FAVORITE_ENTRY_SET] = currentKeys + entryKey
            prefs[favFileIdKey(entryKey)] = cleanFileId
            prefs[favSectionIdKey(entryKey)] = file.sectionId.trim()
            prefs[favTitleKey(entryKey)] = file.title.trim()
            val cleanDesc = file.description?.trim().orEmpty()
            if (cleanDesc.isNotEmpty()) {
                prefs[favDescriptionKey(entryKey)] = cleanDesc
            } else {
                prefs.remove(favDescriptionKey(entryKey))
            }
            val cleanVersionId = file.activeVersionId?.trim().orEmpty()
            if (cleanVersionId.isNotEmpty()) {
                prefs[favVersionIdKey(entryKey)] = cleanVersionId
            } else {
                prefs.remove(favVersionIdKey(entryKey))
            }
            val cleanVersionLabel = file.activeVersionLabel?.trim().orEmpty()
            if (cleanVersionLabel.isNotEmpty()) {
                prefs[favVersionLabelKey(entryKey)] = cleanVersionLabel
            } else {
                prefs.remove(favVersionLabelKey(entryKey))
            }
            if (!prefs.contains(favSavedAtKey(entryKey))) {
                prefs[favSavedAtKey(entryKey)] = savedAt
            }
        }
    }

    suspend fun removeFavorite(fileId: String) {
        val cleanFileId = fileId.trim()
        if (cleanFileId.isEmpty()) return
        val entryKey = encodeHex(cleanFileId)

        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_FAVORITE_ENTRY_SET].orEmpty()
            prefs[KEY_FAVORITE_ENTRY_SET] = currentKeys - entryKey
            prefs.remove(favFileIdKey(entryKey))
            prefs.remove(favSectionIdKey(entryKey))
            prefs.remove(favTitleKey(entryKey))
            prefs.remove(favDescriptionKey(entryKey))
            prefs.remove(favVersionIdKey(entryKey))
            prefs.remove(favVersionLabelKey(entryKey))
            prefs.remove(favSavedAtKey(entryKey))
        }
    }

    suspend fun toggleFavorite(file: StudyFileMetadata) {
        val cleanFileId = file.id.trim()
        if (cleanFileId.isEmpty()) return
        val entryKey = encodeHex(cleanFileId)
        val savedAt = clockEpochMillis()

        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_FAVORITE_ENTRY_SET].orEmpty()
            if (currentKeys.contains(entryKey)) {
                prefs[KEY_FAVORITE_ENTRY_SET] = currentKeys - entryKey
                prefs.remove(favFileIdKey(entryKey))
                prefs.remove(favSectionIdKey(entryKey))
                prefs.remove(favTitleKey(entryKey))
                prefs.remove(favDescriptionKey(entryKey))
                prefs.remove(favVersionIdKey(entryKey))
                prefs.remove(favVersionLabelKey(entryKey))
                prefs.remove(favSavedAtKey(entryKey))
            } else {
                prefs[KEY_FAVORITE_ENTRY_SET] = currentKeys + entryKey
                prefs[favFileIdKey(entryKey)] = cleanFileId
                prefs[favSectionIdKey(entryKey)] = file.sectionId.trim()
                prefs[favTitleKey(entryKey)] = file.title.trim()
                val cleanDesc = file.description?.trim().orEmpty()
                if (cleanDesc.isNotEmpty()) {
                    prefs[favDescriptionKey(entryKey)] = cleanDesc
                } else {
                    prefs.remove(favDescriptionKey(entryKey))
                }
                val cleanVersionId = file.activeVersionId?.trim().orEmpty()
                if (cleanVersionId.isNotEmpty()) {
                    prefs[favVersionIdKey(entryKey)] = cleanVersionId
                } else {
                    prefs.remove(favVersionIdKey(entryKey))
                }
                val cleanVersionLabel = file.activeVersionLabel?.trim().orEmpty()
                if (cleanVersionLabel.isNotEmpty()) {
                    prefs[favVersionLabelKey(entryKey)] = cleanVersionLabel
                } else {
                    prefs.remove(favVersionLabelKey(entryKey))
                }
                prefs[favSavedAtKey(entryKey)] = savedAt
            }
        }
    }

    fun observeCustomOrder(scopeKey: String): Flow<List<String>> {
        val cleanScope = scopeKey.trim()
        if (cleanScope.isEmpty()) return safePrefsFlow().map { emptyList() }
        val key = orderScopeKey(cleanScope)
        return safePrefsFlow().map { prefs ->
            decodeOrderedIdList(prefs[key])
        }
    }

    suspend fun saveCustomOrder(scopeKey: String, orderedFileIds: List<String>) {
        val cleanScope = scopeKey.trim()
        if (cleanScope.isEmpty()) return
        val normalizedIds = orderedFileIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val key = orderScopeKey(cleanScope)

        dataStore.edit { prefs ->
            if (normalizedIds.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = normalizedIds.joinToString(separator = ",") { encodeHex(it) }
            }
        }
    }

    suspend fun clearCustomOrder(scopeKey: String) {
        val cleanScope = scopeKey.trim()
        if (cleanScope.isEmpty()) return
        val key = orderScopeKey(cleanScope)
        dataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    private fun safePrefsFlow(): Flow<Preferences> {
        return dataStore.data.catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
    }

    private fun readFavorites(prefs: Preferences): List<FavoriteStudyFile> {
        val entryKeys = prefs[KEY_FAVORITE_ENTRY_SET].orEmpty()
        if (entryKeys.isEmpty()) return emptyList()
        return entryKeys
            .mapNotNull { entryKey ->
                val fileId = prefs[favFileIdKey(entryKey)]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val title = prefs[favTitleKey(entryKey)]?.trim().orEmpty()
                FavoriteStudyFile(
                    fileId = fileId,
                    sectionId = prefs[favSectionIdKey(entryKey)]?.trim().orEmpty(),
                    title = title,
                    description = prefs[favDescriptionKey(entryKey)],
                    activeVersionId = prefs[favVersionIdKey(entryKey)],
                    activeVersionLabel = prefs[favVersionLabelKey(entryKey)],
                    savedAtEpochMillis = prefs[favSavedAtKey(entryKey)] ?: 0L
                )
            }
            .sortedWith(
                compareByDescending<FavoriteStudyFile> { it.savedAtEpochMillis }
                    .thenBy { it.title }
            )
    }

    private fun decodeOrderedIdList(encoded: String?): List<String> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(',')
            .mapNotNull { token -> decodeHexOrNull(token) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun encodeHex(value: String): String {
        return value.trim()
            .toByteArray(Charsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun decodeHexOrNull(hex: String): String? {
        val clean = hex.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            val bytes = ByteArray(clean.length / 2)
            for (i in bytes.indices) {
                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun favFileIdKey(entryKey: String) = stringPreferencesKey("fav_file_id_$entryKey")
    private fun favSectionIdKey(entryKey: String) = stringPreferencesKey("fav_sec_id_$entryKey")
    private fun favTitleKey(entryKey: String) = stringPreferencesKey("fav_title_$entryKey")
    private fun favDescriptionKey(entryKey: String) = stringPreferencesKey("fav_desc_$entryKey")
    private fun favVersionIdKey(entryKey: String) = stringPreferencesKey("fav_ver_id_$entryKey")
    private fun favVersionLabelKey(entryKey: String) = stringPreferencesKey("fav_ver_lbl_$entryKey")
    private fun favSavedAtKey(entryKey: String) = longPreferencesKey("fav_saved_at_$entryKey")
    private fun orderScopeKey(scopeKey: String) = stringPreferencesKey("order_scope_${encodeHex(scopeKey)}")

    private companion object {
        val KEY_FAVORITE_ENTRY_SET = stringSetPreferencesKey("local_favorite_entry_keys")
    }
}
