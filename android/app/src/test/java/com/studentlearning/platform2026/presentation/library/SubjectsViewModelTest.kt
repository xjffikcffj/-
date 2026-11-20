package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.fakes.FakeCatalogRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubjectsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads subjects for requested branchId`() = runTest {
        val subjects = listOf(
            StudySubject("math", "scientific", "الرياضيات", 1, true),
            StudySubject("physics", "scientific", "الفيزياء", 6, true)
        )
        val repository = FakeCatalogRepository(
            subjectsResult = { requestedBranch ->
                if (requestedBranch == "scientific") AppResult.Success(subjects)
                else AppResult.Success(emptyList())
            }
        )

        val viewModel = SubjectsViewModel("scientific", repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SubjectsUiState.Content)
        assertEquals(subjects, (state as SubjectsUiState.Content).subjects)
    }

    @Test
    fun `shows Error and retries successfully`() = runTest {
        var shouldFail = true
        val subjects = listOf(
            StudySubject("economics", "literary", "الاقتصاد", 7, true)
        )
        val repository = FakeCatalogRepository(
            subjectsResult = {
                if (shouldFail) {
                    AppResult.Error("Offline", CatalogException(CatalogFailureReason.NETWORK_UNAVAILABLE))
                } else {
                    AppResult.Success(subjects)
                }
            }
        )

        val viewModel = SubjectsViewModel("literary", repository)
        advanceUntilIdle()

        assertEquals(
            CatalogFailureReason.NETWORK_UNAVAILABLE,
            (viewModel.uiState.value as SubjectsUiState.Error).reason
        )

        shouldFail = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(subjects, (viewModel.uiState.value as SubjectsUiState.Content).subjects)
    }
}
