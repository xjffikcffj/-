package com.studentlearning.platform2026.presentation.notifications

import com.studentlearning.platform2026.domain.model.LocalNotificationItem
import com.studentlearning.platform2026.domain.model.StudyNotificationType
import com.studentlearning.platform2026.fakes.FakeNotificationLogRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `observes incoming notifications and supports read, delete, and clear operations`() = runTest {
        val repo = FakeNotificationLogRepository(
            initialItems = listOf(
                LocalNotificationItem(
                    id = "notif-1",
                    type = StudyNotificationType.NEW_FILE,
                    title = "ملف جديد",
                    body = "تمت إضافة ملخص",
                    fileId = "file-1",
                    versionId = "v1",
                    receivedAtEpochMillis = 1_700_000_001_000L,
                    isRead = false
                ),
                LocalNotificationItem(
                    id = "notif-2",
                    type = StudyNotificationType.NEW_VERSION,
                    title = "إصدار محدث",
                    body = "إصدار 2.0 متاح",
                    fileId = "file-2",
                    versionId = "v2",
                    receivedAtEpochMillis = 1_700_000_002_000L,
                    isRead = false
                )
            )
        )
        val viewModel = NotificationsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.unreadCount)
        assertEquals(listOf("notif-2", "notif-1"), viewModel.uiState.value.notifications.map { it.id })

        viewModel.markAsRead("notif-2")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.unreadCount)
        assertTrue(viewModel.uiState.value.notifications.first { it.id == "notif-2" }.isRead)

        viewModel.deleteNotification("notif-1")
        advanceUntilIdle()
        assertEquals(listOf("notif-2"), viewModel.uiState.value.notifications.map { it.id })

        viewModel.markAllAsRead()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.unreadCount)

        viewModel.clearAll()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.notifications.isEmpty())
    }
}
