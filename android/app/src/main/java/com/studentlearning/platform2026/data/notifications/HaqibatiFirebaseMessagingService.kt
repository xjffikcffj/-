package com.studentlearning.platform2026.data.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.studentlearning.platform2026.HaqibatiApplication
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import com.studentlearning.platform2026.domain.model.StudyNotificationType
import com.studentlearning.platform2026.domain.repository.NotificationLogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.UUID

internal object IncomingFcmMessageHandler {

    suspend fun handleIncomingPayload(
        data: Map<String, String>,
        fallbackTitle: String?,
        fallbackBody: String?,
        defaultTitle: String,
        defaultBody: String,
        remoteMessageId: String?,
        remoteSentTimeMillis: Long,
        notificationLogRepository: NotificationLogRepository?,
        nowMillisProvider: () -> Long = { System.currentTimeMillis() },
        uuidProvider: () -> String = { UUID.randomUUID().toString() },
        showSystemNotification: (LocalNotificationItem) -> Unit
    ): LocalNotificationItem {
        val type = StudyNotificationType.fromWireValue(data["type"])
        val resolvedTitle = data["title"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultTitle
        val resolvedBody = data["body"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackBody?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultBody

        val notificationId = data["notificationId"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: remoteMessageId?.trim()?.takeIf { it.isNotEmpty() }
            ?: uuidProvider()
        val receivedAt = data["sentAtEpochMillis"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: remoteSentTimeMillis.takeIf { it > 0L }
            ?: nowMillisProvider()

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

        // Always persist to the local log first, independent of POST_NOTIFICATIONS permission,
        // and deduplicate by notificationId.
        var shouldDisplaySystemNotification = true
        if (notificationLogRepository != null) {
            try {
                shouldDisplaySystemNotification =
                    notificationLogRepository.recordIncomingNotificationIfNew(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort local log persistence; never crash messaging service.
            }
        }

        if (shouldDisplaySystemNotification) {
            try {
                showSystemNotification(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Never fail messaging service if notification display is blocked or throws.
            }
        }
        return item
    }
}

class HaqibatiFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val notification = remoteMessage.notification
        val appContainer = (applicationContext as? HaqibatiApplication)?.appContainer
        val defaultTitle = getString(R.string.notifications_default_title)
        val defaultBody = getString(R.string.notifications_default_body)

        runBlocking {
            IncomingFcmMessageHandler.handleIncomingPayload(
                data = remoteMessage.data,
                fallbackTitle = notification?.title,
                fallbackBody = notification?.body,
                defaultTitle = defaultTitle,
                defaultBody = defaultBody,
                remoteMessageId = remoteMessage.messageId,
                remoteSentTimeMillis = remoteMessage.sentTime,
                notificationLogRepository = appContainer?.notificationLogRepository,
                showSystemNotification = { item ->
                    NotificationChannelHelper.showSystemNotificationIfPermitted(
                        applicationContext,
                        item
                    )
                }
            )
        }
    }

    override fun onNewToken(token: String) {
        val appContainer = (applicationContext as? HaqibatiApplication)?.appContainer ?: return
        NotificationChannelHelper.subscribeToUpdatesTopicIfConfigured(
            appContainer.firebaseClientProvider
        )
    }
}
