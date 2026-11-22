package com.studentlearning.platform2026.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import com.studentlearning.platform2026.domain.model.StudyNotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNotificationDataSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `records supports NEW_FILE NEW_VERSION IMPORTANT_UPDATE persists across restarts and supports read and delete`() =
        runTest {
            val storeFile = File(tempFolder.newFolder("prefs_notif"), "local_notifications.preferences_pb")
            val item1 = LocalNotificationItem(
                id = "n1",
                type = StudyNotificationType.NEW_FILE,
                title = "ملف جديد",
                body = "تم نشر ملزمة الفيزياء",
                fileId = "file-1",
                versionId = "v1",
                versionLabel = "1.0",
                receivedAtEpochMillis = 1_700_000_001_000L,
                isRead = false
            )
            val item2 = LocalNotificationItem(
                id = "n2",
                type = StudyNotificationType.NEW_VERSION,
                title = "إصدار جديد",
                body = "تحديث ملزمة الرياضيات v2.0",
                fileId = "file-2",
                versionId = "v2",
                versionLabel = "2.0",
                receivedAtEpochMillis = 1_700_000_002_000L,
                isRead = false
            )
            val item3 = LocalNotificationItem(
                id = "n3",
                type = StudyNotificationType.IMPORTANT_UPDATE,
                title = "تنبيه مهم",
                body = "تم تحديث جدول المراجعة",
                receivedAtEpochMillis = 1_700_000_003_000L,
                isRead = false
            )

            val scope1 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val ds1 = LocalNotificationDataSource(
                PreferenceDataStoreFactory.create(scope = scope1, produceFile = { storeFile })
            )
            ds1.recordIncomingNotification(item1)
            ds1.recordIncomingNotification(item2)
            ds1.recordIncomingNotification(item3)
            ds1.markAsRead("n2")
            assertEquals(2, ds1.observeUnreadCount().first())
            scope1.cancel()

            // Re-open on simulated restart
            val scope2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val ds2 = LocalNotificationDataSource(
                PreferenceDataStoreFactory.create(scope = scope2, produceFile = { storeFile })
            )
            val loaded = ds2.observeNotifications().first()
            assertEquals(listOf("n3", "n2", "n1"), loaded.map { it.id })
            assertTrue(loaded.first { it.id == "n2" }.isRead)
            assertFalse(loaded.first { it.id == "n1" }.isRead)

            ds2.deleteNotification("n1")
            assertEquals(listOf("n3", "n2"), ds2.observeNotifications().first().map { it.id })

            ds2.markAllAsRead()
            assertEquals(0, ds2.observeUnreadCount().first())

            ds2.clearAll()
            assertTrue(ds2.observeNotifications().first().isEmpty())
            scope2.cancel()
        }
}
