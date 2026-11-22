package com.studentlearning.platform2026.data.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.studentlearning.platform2026.HaqibatiApplication
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import com.studentlearning.platform2026.domain.model.StudyNotificationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.UUID

class HaqibatiFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val type = StudyNotificationType.fromWireValue(data["type"])
        val resolvedTitle = data["title"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: notification?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.notifications_default_title)
        val resolvedBody = data["body"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: notification?.body?.trim()?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.notifications_default_body)

        val notificationId = data["notificationId"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: remoteMessage.messageId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        val receivedAt = data["sentAtEpochMillis"]?.toLongOrNull()
            ?: remoteMessage.sentTime.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        val item = LocalNotificationItem(
            id = notificationId,
            type = type,
            title = resolvedTitle,
            body = resolvedBody,
            fileId = data["fileId"]?.trim()?.takeIf { it.isNotEmpty() },
            versionId = data["versionId"]?.trim()?.takeIf { it.isNotEmpty() },
            versionLabel = data["versionLabel"]?.trim()?.takeIf { it.isNotEmpty() },
            receivedAtEpochMillis = receivedAt,
            isRead = false
        )

        val appContainer = (applicationContext as? HaqibatiApplication)?.appContainer
        if (appContainer != null) {
            try {
                runBlocking {
                    appContainer.notificationLogRepository.recordIncomingNotification(item)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort local log persistence; never crash messaging service.
            }
        }

        NotificationChannelHelper.showSystemNotificationIfPermitted(applicationContext, item)
    }

    override fun onNewToken(token: String) {
        val appContainer = (applicationContext as? HaqibatiApplication)?.appContainer ?: return
        NotificationChannelHelper.subscribeToUpdatesTopicIfConfigured(
            appContainer.firebaseClientProvider
        )
    }
}
