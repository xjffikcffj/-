package com.studentlearning.platform2026.presentation.downloads

import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial downloads state is initialized without fake saved files`() {
        val viewModel = DownloadsViewModel()
        val state = viewModel.uiState.value

        assertTrue(state.isInitialized)
        assertFalse(state.hasSavedFiles)
        assertEquals(AppUserRole.GUEST, state.userRole)
        assertFalse(state.canDownloadOfflineFiles)
    }

    @Test
    fun `downloads capability reflects guest restriction vs signed in student`() = runTest {
        val authRepository = FakeAuthRepository(initialSession = AdminSession())
        val viewModel = DownloadsViewModel(authRepository = authRepository)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        assertEquals(AppUserRole.GUEST, viewModel.uiState.value.userRole)
        assertFalse(viewModel.uiState.value.canDownloadOfflineFiles)

        authRepository.setSession(
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        advanceUntilIdle()

        assertEquals(AppUserRole.STUDENT, viewModel.uiState.value.userRole)
        assertTrue(viewModel.uiState.value.canDownloadOfflineFiles)
    }
}
