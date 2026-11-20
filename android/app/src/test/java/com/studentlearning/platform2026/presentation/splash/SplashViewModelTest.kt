package com.studentlearning.platform2026.presentation.splash

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.UserPreferences
import com.studentlearning.platform2026.domain.repository.PreferencesRepository
import com.studentlearning.platform2026.fakes.FakePreferencesRepository
import com.studentlearning.platform2026.fakes.FakeSystemStatusRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    @Test
    fun `splash initializes preferences and transitions to ready state`() = runTest {
        val preferencesRepository = FakePreferencesRepository(
            initialPreferences = UserPreferences(hasCompletedInitialLaunch = false)
        )
        val systemStatusRepository = FakeSystemStatusRepository()

        val viewModel = SplashViewModel(
            preferencesRepository = preferencesRepository,
            systemStatusRepository = systemStatusRepository,
            dispatcherProvider = testDispatchers,
            minimumDisplayDurationMs = 0L
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isReadyToNavigate)
        val successResult = state.bootstrapResult as AppResult.Success
        assertEquals(
            FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE,
            successResult.data.firebaseReadiness.status
        )
        assertTrue(preferencesRepository.preferencesFlow.first().hasCompletedInitialLaunch)
    }

    @Test
    fun `splash exposes error state and recovers on retry`() = runTest {
        var shouldFail = true
        val delegate = FakePreferencesRepository()
        val flakyRepository = object : PreferencesRepository {
            override val preferencesFlow: Flow<UserPreferences> = flow {
                if (shouldFail) {
                    throw IllegalStateException("Storage unavailable")
                }
                emit(delegate.preferencesFlow.first())
            }

            override suspend fun setDarkModeEnabled(enabled: Boolean) =
                delegate.setDarkModeEnabled(enabled)

            override suspend fun markInitialLaunchCompleted() =
                delegate.markInitialLaunchCompleted()
        }

        val viewModel = SplashViewModel(
            preferencesRepository = flakyRepository,
            systemStatusRepository = FakeSystemStatusRepository(),
            dispatcherProvider = testDispatchers,
            minimumDisplayDurationMs = 0L
        )

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isReadyToNavigate)
        assertTrue(viewModel.uiState.value.bootstrapResult is AppResult.Error)

        shouldFail = false
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isReadyToNavigate)
        assertTrue(viewModel.uiState.value.bootstrapResult is AppResult.Success)
    }
}
