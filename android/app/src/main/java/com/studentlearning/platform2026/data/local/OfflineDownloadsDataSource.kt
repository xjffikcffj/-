package com.studentlearning.platform2026.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.haqibatiOfflineDownloadsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "haqibati_offline_downloads"
)

/**
 * Strictly device-local DataStore persistence for completed offline PDF versions.
 *
 * Security & privacy guarantees:
 * - Keys are derived exclusively from hex-encoded (`fileId`, `versionId`) pairs so different
 *   versions of the same file and different files with identical titles never collide.
 * - NEVER stores Firebase Auth UIDs or user emails, and NEVER syncs with Firestore.
 * - Signing out or switching accounts never clears or alters stored offline records.
 */
class OfflineDownloadsDataSource(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(dataStore = context.haqibatiOfflineDownloadsDataStore)

    fun observeAllRecords(): Flow<List<LocalDownloadedFileVersion>> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs -> readAllRecordsFromPreferences(prefs) }
    }

    suspend fun getAllRecords(): List<LocalDownloadedFileVersion> {
        return observeAllRecords().first()
    }

    suspend fun getRecordsForFile(fileId: String): List<LocalDownloadedFileVersion> {
        if (fileId.isBlank()) return emptyList()
        return getAllRecords()
            .filter { it.fileId == fileId }
            .sortedWith(versionComparator())
    }

    suspend fun getRecord(fileId: String, versionId: String): LocalDownloadedFileVersion? {
        if (fileId.isBlank() || versionId.isBlank()) return null
        val prefs = dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .first()
        val entryKey = encodeEntryKey(fileId, versionId)
        val keys = prefs[KEY_ENTRY_SET].orEmpty()
        if (!keys.contains(entryKey)) return null
        return readSingleRecord(prefs, entryKey)
    }

    suspend fun saveCompletedRecord(record: LocalDownloadedFileVersion) {
        if (record.fileId.isBlank() || record.versionId.isBlank()) return
        val entryKey = encodeEntryKey(record.fileId, record.versionId)

        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_ENTRY_SET].orEmpty()
            prefs[KEY_ENTRY_SET] = currentKeys + entryKey
            prefs[fileIdKey(entryKey)] = record.fileId
            prefs[versionIdKey(entryKey)] = record.versionId
            if (record.versionNumber != null) {
                prefs[versionNumberKey(entryKey)] = record.versionNumber
            } else {
                prefs.remove(versionNumberKey(entryKey))
            }
            if (!record.versionLabel.isNullOrBlank()) {
                prefs[versionLabelKey(entryKey)] = record.versionLabel
            } else {
                prefs.remove(versionLabelKey(entryKey))
            }
            prefs[titleKey(entryKey)] = record.fileTitle
            if (!record.sectionId.isNullOrBlank()) {
                prefs[sectionIdKey(entryKey)] = record.sectionId
            } else {
                prefs.remove(sectionIdKey(entryKey))
            }
            prefs[localPathKey(entryKey)] = record.localFilePath
            prefs[fileSizeKey(entryKey)] = record.fileSizeBytes
            prefs[checksumKey(entryKey)] = record.checksumSha256
            prefs[downloadedAtKey(entryKey)] = record.downloadedAtEpochMillis
        }
    }

    suspend fun removeRecord(fileId: String, versionId: String) {
        if (fileId.isBlank() || versionId.isBlank()) return
        val entryKey = encodeEntryKey(fileId, versionId)

        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_ENTRY_SET].orEmpty()
            prefs[KEY_ENTRY_SET] = currentKeys - entryKey
            prefs.remove(fileIdKey(entryKey))
            prefs.remove(versionIdKey(entryKey))
            prefs.remove(versionNumberKey(entryKey))
            prefs.remove(versionLabelKey(entryKey))
            prefs.remove(titleKey(entryKey))
            prefs.remove(sectionIdKey(entryKey))
            prefs.remove(localPathKey(entryKey))
            prefs.remove(fileSizeKey(entryKey))
            prefs.remove(checksumKey(entryKey))
            prefs.remove(downloadedAtKey(entryKey))
        }
    }

    private fun readAllRecordsFromPreferences(prefs: Preferences): List<LocalDownloadedFileVersion> {
        val entryKeys = prefs[KEY_ENTRY_SET].orEmpty()
        if (entryKeys.isEmpty()) return emptyList()
        return entryKeys
            .mapNotNull { entryKey -> readSingleRecord(prefs, entryKey) }
            .sortedWith(versionComparator())
    }

    private fun readSingleRecord(
        prefs: Preferences,
        entryKey: String
    ): LocalDownloadedFileVersion? {
        val fileId = prefs[fileIdKey(entryKey)]?.takeIf { it.isNotBlank() } ?: return null
        val versionId = prefs[versionIdKey(entryKey)]?.takeIf { it.isNotBlank() } ?: return null
        val localPath = prefs[localPathKey(entryKey)]?.takeIf { it.isNotBlank() } ?: return null
        val fileSize = prefs[fileSizeKey(entryKey)] ?: return null
        if (fileSize <= 0L) return null

        return LocalDownloadedFileVersion(
            fileId = fileId,
            versionId = versionId,
            versionNumber = prefs[versionNumberKey(entryKey)],
            versionLabel = prefs[versionLabelKey(entryKey)],
            fileTitle = prefs[titleKey(entryKey)].orEmpty(),
            sectionId = prefs[sectionIdKey(entryKey)],
            localFilePath = localPath,
            fileSizeBytes = fileSize,
            checksumSha256 = prefs[checksumKey(entryKey)].orEmpty(),
            downloadedAtEpochMillis = prefs[downloadedAtKey(entryKey)] ?: 0L
        )
    }

    private fun versionComparator(): Comparator<LocalDownloadedFileVersion> =
        compareByDescending<LocalDownloadedFileVersion> { it.versionNumber ?: Int.MIN_VALUE }
            .thenByDescending { it.downloadedAtEpochMillis }

    private fun encodeEntryKey(fileId: String, versionId: String): String {
        return "${encodeHex(fileId)}_${encodeHex(versionId)}"
    }

    private fun encodeHex(value: String): String {
        return value.trim()
            .toByteArray(Charsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun fileIdKey(entryKey: String) = stringPreferencesKey("dl_file_id_$entryKey")
    private fun versionIdKey(entryKey: String) = stringPreferencesKey("dl_ver_id_$entryKey")
    private fun versionNumberKey(entryKey: String) = intPreferencesKey("dl_ver_num_$entryKey")
    private fun versionLabelKey(entryKey: String) = stringPreferencesKey("dl_ver_lbl_$entryKey")
    private fun titleKey(entryKey: String) = stringPreferencesKey("dl_title_$entryKey")
    private fun sectionIdKey(entryKey: String) = stringPreferencesKey("dl_sec_id_$entryKey")
    private fun localPathKey(entryKey: String) = stringPreferencesKey("dl_path_$entryKey")
    private fun fileSizeKey(entryKey: String) = longPreferencesKey("dl_size_$entryKey")
    private fun checksumKey(entryKey: String) = stringPreferencesKey("dl_sha256_$entryKey")
    private fun downloadedAtKey(entryKey: String) = longPreferencesKey("dl_at_$entryKey")

    private companion object {
        val KEY_ENTRY_SET = stringSetPreferencesKey("offline_download_entry_keys")
    }
}
