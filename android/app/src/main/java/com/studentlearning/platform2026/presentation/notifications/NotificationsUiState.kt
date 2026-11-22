package com.studentlearning.platform2026.presentation.notifications

import com.studentlearning.platform2026.domain.model.LocalNotificationItem

data class NotificationsUiState(
    val notifications: List<LocalNotificationItem> = emptyList(),
    val unreadCount: Int = 0
)
