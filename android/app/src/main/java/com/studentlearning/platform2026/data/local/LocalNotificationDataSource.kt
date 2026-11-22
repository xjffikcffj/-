package com.studentlearning.platform2026.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import com.studentlearning.platform2026.domain.model.StudyNotificationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.haqibatiNotificationsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "haqibati_local_notifications"
)

/**
 * Strictly device-local DataStore persistence for incoming FCM study notifications.
 *
 * Security & privacy guarantees:
 * - Zero Firebase Auth UIDs or user emails stored.
 * - Independent of account state or Firestore sync.
 * - Supports per-item read tracking and local deletion cleanly.
 */
class LocalNotificationDataSource(
    private val dataStore: DataStore<Preferences>,
    private val maxStoredNotifications: Int = DEFAULT_MAX_STORED_NOTIFICATIONS
) {
    constructor(context: Context) : this(dataStore = context.haqibatiNotificationsDataStore)

    fun observeNotifications(): Flow<List<LocalNotificationItem>> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs -> readAll(prefs) }
    }

    fun observeUnreadCount(): Flow<Int> {
        return observeNotifications().map { list -> list.count { !it.isRead } }
    }

    suspend fun saveNotification(item: LocalNotificationItem) {
        val cleanId = item.id.trim()
        if (cleanId.isEmpty()) return
        val entryKey = encodeHex(cleanId)

        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_NOTIFICATION_ENTRY_SET].orEmpty()
            prefs[KEY_NOTIFICATION_ENTRY_SET] = currentKeys + entryKey
            prefs[idKey(entryKey)] = cleanId
            prefs[typeKey(entryKey)] = item.type.name
            prefs[titleKey(entryKey)] = item.title.trim()
            prefs[bodyKey(entryKey)] = item.body.trim()
            val cleanFileId = item.fileId?.trim().orEmpty()
            if (cleanFileId.isNotEmpty()) {
                prefs[fileIdKey(entryKey)] = cleanFileId
            } else {
                prefs.remove(fileIdKey(entryKey))
            }
            val cleanVersionId = item.versionId?.trim().orEmpty()
            if (cleanVersionId.isNotEmpty()) {
                prefs[versionIdKey(entryKey)] = cleanVersionId
            } else {
                prefs.remove(versionIdKey(entryKey))
            }
            val cleanVersionLabel = item.versionLabel?.trim().orEmpty()
            if (cleanVersionLabel.isNotEmpty()) {
                prefs[versionLabelKey(entryKey)] = cleanVersionLabel
            } else {
                prefs.remove(versionLabelKey(entryKey))
            }
            prefs[receivedAtKey(entryKey)] = item.receivedAtEpochMillis
            prefs[isReadKey(entryKey)] = item.isRead

            trimOverflowEntries(prefs)
        }
    }

    suspend fun markAsRead(notificationId: String) {
        val cleanId = notificationId.trim()
        if (cleanId.isEmpty()) return
        val entryKey = encodeHex(cleanId)
        dataStore.edit { prefs ->
            if (prefs[KEY_NOTIFICATION_ENTRY_SET].orEmpty().contains(entryKey)) {
                prefs[isReadKey(entryKey)] = true
            }
        }
    }

    suspend fun markAllAsRead() {
        dataStore.edit { prefs ->
            val entryKeys = prefs[KEY_NOTIFICATION_ENTRY_SET].orEmpty()
            for (entryKey in entryKeys) {
                prefs[isReadKey(entryKey)] = true
            }
        }
    }

    suspend fun deleteNotification(notificationId: String) {
        val cleanId = notificationId.trim()
        if (cleanId.isEmpty()) return
        val entryKey = encodeHex(cleanId)
        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_NOTIFICATION_ENTRY_SET].orEmpty()
            prefs[KEY_NOTIFICATION_ENTRY_SET] = currentKeys - entryKey
            removeEntryFields(prefs, entryKey)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            val currentKeys = prefs[KEY_NOTIFICATION_ENTRY_SET].orEmpty()
            for (entryKey in currentKeys) {
                removeEntryFields(prefs, entryKey)
            }
            prefs.remove(KEY_NOTIFICATION_ENTRY_SET)
        }
    }

    private fun trimOverflowEntries(prefs: MutablePreferences) {
        val items = readAll(prefs)
        if (items.size <= maxStoredNotifications) return
        val toRemove = items.drop(maxStoredNotifications)
        var currentKeys = prefs[KEY_NOTIFICATION_ENTRY_SET].orEmpty()
        for (item in toRemove) {
            val entryKey = encodeHex(item.id)
            currentKeys = currentKeys - entryKey
            removeEntryFields(prefs, entryKey)
        }
        prefs[KEY_NOTIFICATION_ENTRY_SET] = currentKeys
    }

    private fun removeEntryFields(prefs: MutablePreferences, entryKey: String) {
        prefs.remove(idKey(entryKey))
        prefs.remove(typeKey(entryKey))
        prefs.remove(titleKey(entryKey))
        prefs.remove(bodyKey(entryKey))
        prefs.remove(fileIdKey(entryKey))
        prefs.remove(versionIdKey(entryKey))
        prefs.remove(versionLabelKey(entryKey))
        prefs.remove(receivedAtKey(entryKey))
        prefs.remove(isReadKey(entryKey))
    }

    private fun readAll(prefs: Preferences): List<LocalNotificationItem> {
        val entryKeys = prefs[KEY_NOTIFICATION_ENTRY_SET].orEmpty()
        if (entryKeys.isEmpty()) return emptyList()
        return entryKeys
            .mapNotNull { entryKey ->
                val id = prefs[idKey(entryKey)]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val title = prefs[titleKey(entryKey)]?.trim().orEmpty()
                val body = prefs[bodyKey(entryKey)]?.trim().orEmpty()
                LocalNotificationItem(
                    id = id,
                    type = StudyNotificationType.fromWireValue(prefs[typeKey(entryKey)]),
                    title = title,
                    body = body,
                    fileId = prefs[fileIdKey(entryKey)],
                    versionId = prefs[versionIdKey(entryKey)],
                    versionLabel = prefs[versionLabelKey(entryKey)],
                    receivedAtEpochMillis = prefs[receivedAtKey(entryKey)] ?: 0L,
                    isRead = prefs[isReadKey(entryKey)] ?: false
                )
            }
            .sortedWith(
                compareByDescending<LocalNotificationItem> { it.receivedAtEpochMillis }
                    .thenBy { it.id }
            )
    }

    private fun encodeHex(value: String): String {
        return value.trim()
            .toByteArray(Charsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun idKey(entryKey: String) = stringPreferencesKey("notif_id_$entryKey")
    private fun typeKey(entryKey: String) = stringPreferencesKey("notif_type_$entryKey")
    private fun titleKey(entryKey: String) = stringPreferencesKey("notif_title_$entryKey")
    private fun bodyKey(entryKey: String) = stringPreferencesKey("notif_body_$entryKey")
    private fun fileIdKey(entryKey: String) = stringPreferencesKey("notif_file_id_$entryKey")
    private fun versionIdKey(entryKey: String) = stringPreferencesKey("notif_ver_id_$entryKey")
    private fun versionLabelKey(entryKey: String) = stringPreferencesKey("notif_ver_lbl_$entryKey")
    private fun receivedAtKey(entryKey: String) = longPreferencesKey("notif_at_$entryKey")
    private fun isReadKey(entryKey: String) = booleanPreferencesKey("notif_read_$entryKey")

    private companion object {
        const val DEFAULT_MAX_STORED_NOTIFICATIONS = 100
        val KEY_NOTIFICATION_ENTRY_SET = stringSetPreferencesKey("local_notification_entry_keys")
    }
}
