package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
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
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads published branches into Content state`() = runTest {
        val branches = listOf(
            StudyBranch(id = "scientific", name = "السادس العلمي", order = 1, isPublished = true),
            StudyBranch(id = "literary", name = "السادس الادبي", order = 2, isPublished = true)
        )
        val repository = FakeCatalogRepository(branchesResult = AppResult.Success(branches))

        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LibraryUiState.Content)
        assertEquals(branches, (state as LibraryUiState.Content).branches)
    }

    @Test
    fun `transitions to Empty when no published branches exist`() = runTest {
        val repository = FakeCatalogRepository(branchesResult = AppResult.Success(emptyList()))

        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LibraryUiState.Empty)
    }

    @Test
    fun `transitions to Error on permission failure and recovers on retry`() = runTest {
        val repository = FakeCatalogRepository(
            branchesResult = AppResult.Error(
                message = "Denied",
                cause = CatalogException(CatalogFailureReason.PERMISSION_DENIED)
            )
        )

        val viewModel = LibraryViewModel(repository)
        advanceUntilIdle()

        val errorState = viewModel.uiState.value
        assertTrue(errorState is LibraryUiState.Error)
        assertEquals(CatalogFailureReason.PERMISSION_DENIED, (errorState as LibraryUiState.Error).reason)

        val branches = listOf(
            StudyBranch(id = "scientific", name = "السادس العلمي", order = 1, isPublished = true)
        )
        repository.branchesResult = AppResult.Success(branches)
        viewModel.retry()
        advanceUntilIdle()

        val recovered = viewModel.uiState.value
        assertTrue(recovered is LibraryUiState.Content)
        assertEquals(branches, (recovered as LibraryUiState.Content).branches)
    }
}
