package com.studentlearning.platform2026.presentation.settings

import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.UserPreferences
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.fakes.FakePreferencesRepository
import com.studentlearning.platform2026.fakes.FakeSystemStatusRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `settings reflects initial preferences, readiness, and admin session`() = runTest {
        val preferencesRepository = FakePreferencesRepository()
        val systemStatusRepository = FakeSystemStatusRepository()
        val authRepository = FakeAuthRepository(
            initialSession = AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val viewModel = SettingsViewModel(
            preferencesRepository = preferencesRepository,
            systemStatusRepository = systemStatusRepository,
            authRepository = authRepository
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.preferences.darkModeEnabled)
        assertEquals(
            FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE,
            state.firebaseReadiness?.status
        )
        assertTrue(state.adminSession.hasRecognizedAdminRole)
        assertTrue(state.adminSession.canPerformAdminActions)
        assertEquals(AdminRole.OWNER, state.adminSession.role)
        assertEquals(AppUserRole.OWNER, state.adminSession.userRole)
    }

    @Test
    fun `settings exposes guest when signed out and student when signed in without admin role`() = runTest {
        val authRepository = FakeAuthRepository(initialSession = AdminSession())
        val viewModel = SettingsViewModel(
            preferencesRepository = FakePreferencesRepository(),
            systemStatusRepository = FakeSystemStatusRepository(),
            authRepository = authRepository
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        assertEquals(AppUserRole.GUEST, viewModel.uiState.value.adminSession.userRole)

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

        assertEquals(AppUserRole.STUDENT, viewModel.uiState.value.adminSession.userRole)
        assertFalse(viewModel.uiState.value.adminSession.hasRecognizedAdminRole)
    }

    @Test
    fun `toggling dark mode persists change through repository`() = runTest {
        val preferencesRepository = FakePreferencesRepository()
        val systemStatusRepository = FakeSystemStatusRepository()
        val authRepository = FakeAuthRepository()
        val viewModel = SettingsViewModel(
            preferencesRepository = preferencesRepository,
            systemStatusRepository = systemStatusRepository,
            authRepository = authRepository
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        viewModel.onToggleDarkMode(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.preferences.darkModeEnabled)
    }

    @Test
    fun `sign out ends auth session without clearing local device preferences`() = runTest {
        val preferencesRepository = FakePreferencesRepository(
            initialPreferences = UserPreferences(
                darkModeEnabled = true,
                hasCompletedInitialLaunch = true
            )
        )
        val authRepository = FakeAuthRepository(
            initialSession = AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val viewModel = SettingsViewModel(
            preferencesRepository = preferencesRepository,
            systemStatusRepository = FakeSystemStatusRepository(),
            authRepository = authRepository
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        viewModel.onSignOut()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, authRepository.signOutCalls)
        assertFalse(state.adminSession.isSignedIn)
        assertEquals(AppUserRole.GUEST, state.adminSession.userRole)
        assertTrue(state.signedOutMessageVisible)

        val persistedPrefs = preferencesRepository.preferencesFlow.first()
        assertTrue(persistedPrefs.darkModeEnabled)
        assertTrue(persistedPrefs.hasCompletedInitialLaunch)
    }
}
