package com.studentlearning.platform2026.presentation.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.domain.repository.NotificationLogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotificationsViewModel(
    private val notificationLogRepository: NotificationLogRepository
) : ViewModel() {

    val uiState: StateFlow<NotificationsUiState> = combine(
        notificationLogRepository.observeNotifications(),
        notificationLogRepository.observeUnreadCount()
    ) { items, unread ->
        NotificationsUiState(
            notifications = items,
            unreadCount = unread
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = NotificationsUiState()
    )

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            notificationLogRepository.markAsRead(notificationId)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            notificationLogRepository.markAllAsRead()
        }
    }

    fun deleteNotification(notificationId: String) {
        viewModelScope.launch {
            notificationLogRepository.deleteNotification(notificationId)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            notificationLogRepository.clearAll()
        }
    }

    companion object {
        fun provideFactory(
            notificationLogRepository: NotificationLogRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NotificationsViewModel(
                    notificationLogRepository = notificationLogRepository
                ) as T
            }
        }
    }
}
