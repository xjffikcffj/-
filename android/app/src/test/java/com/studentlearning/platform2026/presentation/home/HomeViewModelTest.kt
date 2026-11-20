package com.studentlearning.platform2026.presentation.home

import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.UserPreferences
import com.studentlearning.platform2026.fakes.FakePreferencesRepository
import com.studentlearning.platform2026.fakes.FakeSystemStatusRepository
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
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `home state reflects arabic language default and firebase awaiting status`() = runTest {
        val preferencesRepository = FakePreferencesRepository()
        val systemStatusRepository = FakeSystemStatusRepository()
        val viewModel = HomeViewModel(
            preferencesRepository = preferencesRepository,
            systemStatusRepository = systemStatusRepository,
            applicationPackageId = "com.studentlearning.platform2026"
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(UserPreferences.DEFAULT_LANGUAGE, state.preferences.preferredLanguage)
        assertFalse(state.preferences.darkModeEnabled)
        assertEquals("com.studentlearning.platform2026", state.applicationPackageId)
        assertEquals(
            FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE,
            state.firebaseReadiness?.status
        )
    }

    @Test
    fun `toggling dark mode updates uiState`() = runTest {
        val preferencesRepository = FakePreferencesRepository()
        val systemStatusRepository = FakeSystemStatusRepository()
        val viewModel = HomeViewModel(
            preferencesRepository = preferencesRepository,
            systemStatusRepository = systemStatusRepository,
            applicationPackageId = "com.studentlearning.platform2026"
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        viewModel.onToggleDarkMode(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.preferences.darkModeEnabled)
    }
}
