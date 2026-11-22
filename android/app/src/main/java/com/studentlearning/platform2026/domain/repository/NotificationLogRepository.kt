package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import kotlinx.coroutines.flow.Flow

interface NotificationLogRepository {
    fun observeNotifications(): Flow<List<LocalNotificationItem>>
    fun observeUnreadCount(): Flow<Int>
    suspend fun recordIncomingNotification(item: LocalNotificationItem)
    suspend fun markAsRead(notificationId: String)
    suspend fun markAllAsRead()
    suspend fun deleteNotification(notificationId: String)
    suspend fun clearAll()
}
