package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.LocalNotificationDataSource
import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import com.studentlearning.platform2026.domain.repository.NotificationLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NotificationLogRepositoryImpl(
    private val localDataSource: LocalNotificationDataSource,
    private val dispatcherProvider: DispatcherProvider
) : NotificationLogRepository {

    override fun observeNotifications(): Flow<List<LocalNotificationItem>> =
        localDataSource.observeNotifications()

    override fun observeUnreadCount(): Flow<Int> =
        localDataSource.observeUnreadCount()

    override suspend fun recordIncomingNotification(item: LocalNotificationItem) {
        withContext(dispatcherProvider.io) {
            localDataSource.saveNotification(item)
        }
    }

    override suspend fun recordIncomingNotificationIfNew(item: LocalNotificationItem): Boolean =
        withContext(dispatcherProvider.io) {
            localDataSource.saveNotificationIfAbsent(item)
        }

    override suspend fun markAsRead(notificationId: String) {
        withContext(dispatcherProvider.io) {
            localDataSource.markAsRead(notificationId)
        }
    }

    override suspend fun markAllAsRead() {
        withContext(dispatcherProvider.io) {
            localDataSource.markAllAsRead()
        }
    }

    override suspend fun deleteNotification(notificationId: String) {
        withContext(dispatcherProvider.io) {
            localDataSource.deleteNotification(notificationId)
        }
    }

    override suspend fun clearAll() {
        withContext(dispatcherProvider.io) {
            localDataSource.clearAll()
        }
    }
}
