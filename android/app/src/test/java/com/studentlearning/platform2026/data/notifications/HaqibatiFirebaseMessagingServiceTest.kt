package com.studentlearning.platform2026.data.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.LocalNotificationDataSource
import com.studentlearning.platform2026.data.repository.NotificationLogRepositoryImpl
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class HaqibatiFirebaseMessagingServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `handles data-only NEW_FILE NEW_VERSION IMPORTANT_UPDATE deduplicates by notificationId and saves locally even when notification display is denied`() =
        runTest {
            val storeFile = File(tempFolder.newFolder("fcm_notif"), "local_notifications.preferences_pb")
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            val dispatcherProvider = object : DispatcherProvider {
                override val main = testDispatcher
                override val io = testDispatcher
                override val default = testDispatcher
            }
            val dataSource = LocalNotificationDataSource(
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { storeFile })
            )
            val repository = NotificationLogRepositoryImpl(
                localDataSource = dataSource,
                dispatcherProvider = dispatcherProvider
            )

            val displayed = mutableListOf<LocalNotificationItem>()

            // 1) NEW_FILE data-only payload (no RemoteMessage.Notification fallback)
            IncomingFcmMessageHandler.handleIncomingPayload(
                data = mapOf(
                    "notificationId" to "notif-new-file-1",
                    "type" to "NEW_FILE",
                    "title" to "ملف دراسي جديد",
                    "body" to "تم نشر ملزمة الفيزياء",
                    "fileId" to "file-1",
                    "versionId" to "v1",
                    "versionLabel" to "1.0",
                    "sentAtEpochMillis" to "1700000001000"
                ),
                fallbackTitle = null,
                fallbackBody = null,
                defaultTitle = "تحديث دراسي جديد",
                defaultBody = "افتح التطبيق",
                remoteMessageId = "fcm-msg-1",
                remoteSentTimeMillis = 1_700_000_001_000L,
                notificationLogRepository = repository,
                showSystemNotification = { item -> displayed.add(item) }
            )

            // 2) Duplicate notificationId must NOT duplicate log or re-display system notification
            IncomingFcmMessageHandler.handleIncomingPayload(
                data = mapOf(
                    "notificationId" to "notif-new-file-1",
                    "type" to "NEW_FILE",
                    "title" to "ملف دراسي جديد",
                    "body" to "تم نشر ملزمة الفيزياء",
                    "fileId" to "file-1",
                    "versionId" to "v1",
                    "versionLabel" to "1.0",
                    "sentAtEpochMillis" to "1700000001000"
                ),
                fallbackTitle = null,
                fallbackBody = null,
                defaultTitle = "تحديث دراسي جديد",
                defaultBody = "افتح التطبيق",
                remoteMessageId = "fcm-msg-1-retry",
                remoteSentTimeMillis = 1_700_000_001_500L,
                notificationLogRepository = repository,
                showSystemNotification = { item -> displayed.add(item) }
            )

            // 3) NEW_VERSION data-only payload
            IncomingFcmMessageHandler.handleIncomingPayload(
                data = mapOf(
                    "notificationId" to "notif-new-ver-2",
                    "type" to "NEW_VERSION",
                    "title" to "إصدار محدث",
                    "body" to "تم تحديث ملزمة الرياضيات إلى 2.0",
                    "fileId" to "file-2",
                    "versionId" to "v2",
                    "versionLabel" to "2.0",
                    "sentAtEpochMillis" to "1700000002000"
                ),
                fallbackTitle = null,
                fallbackBody = null,
                defaultTitle = "تحديث دراسي جديد",
                defaultBody = "افتح التطبيق",
                remoteMessageId = "fcm-msg-2",
                remoteSentTimeMillis = 1_700_000_002_000L,
                notificationLogRepository = repository,
                showSystemNotification = { item -> displayed.add(item) }
            )

            // 4) IMPORTANT_UPDATE when POST_NOTIFICATIONS is denied / notification display throws SecurityException
            IncomingFcmMessageHandler.handleIncomingPayload(
                data = mapOf(
                    "notificationId" to "notif-important-3",
                    "type" to "IMPORTANT_UPDATE",
                    "title" to "تنبيه هام",
                    "body" to "تم نشر جدول الامتحانات",
                    "sentAtEpochMillis" to "1700000003000"
                ),
                fallbackTitle = null,
                fallbackBody = null,
                defaultTitle = "تحديث دراسي جديد",
                defaultBody = "افتح التطبيق",
                remoteMessageId = "fcm-msg-3",
                remoteSentTimeMillis = 1_700_000_003_000L,
                notificationLogRepository = repository,
                showSystemNotification = { _ ->
                    throw SecurityException("POST_NOTIFICATIONS denied")
                }
            )

            val saved = repository.observeNotifications().first()
            assertEquals(3, saved.size)
            assertEquals(
                listOf("notif-important-3", "notif-new-ver-2", "notif-new-file-1"),
                saved.map { it.id }
            )
            assertEquals(
                listOf(
                    StudyNotificationType.IMPORTANT_UPDATE,
                    StudyNotificationType.NEW_VERSION,
                    StudyNotificationType.NEW_FILE
                ),
                saved.map { it.type }
            )
            // System notification callback triggered once for each unique item (skipped on duplicate)
            assertEquals(listOf("notif-new-file-1", "notif-new-ver-2"), displayed.map { it.id })
            // Denying notification permission for notif-important-3 still persisted it locally
            assertTrue(saved.any { it.id == "notif-important-3" && !it.isRead })

            scope.cancel()
        }
}
