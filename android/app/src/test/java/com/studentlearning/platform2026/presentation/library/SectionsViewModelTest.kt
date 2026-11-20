package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudySection
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
class SectionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `transitions to Empty when subject has no published sections`() = runTest {
        val repository = FakeCatalogRepository(
            sectionsResult = { _ -> AppResult.Success(emptyList()) }
        )

        val viewModel = SectionsViewModel("physics", repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SectionsUiState.Empty)
    }

    @Test
    fun `loads published sections and handles retry after failure`() = runTest {
        var shouldFail = true
        val sections = listOf(
            StudySection(
                id = "sec1",
                subjectId = "physics",
                name = "الفصل الأول",
                order = 1,
                isPublished = true
            )
        )
        val repository = FakeCatalogRepository(
            sectionsResult = { _ ->
                if (shouldFail) {
                    AppResult.Error("Not configured", CatalogException(CatalogFailureReason.FIREBASE_NOT_CONFIGURED))
                } else {
                    AppResult.Success(sections)
                }
            }
        )

        val viewModel = SectionsViewModel("physics", repository)
        advanceUntilIdle()

        assertEquals(
            CatalogFailureReason.FIREBASE_NOT_CONFIGURED,
            (viewModel.uiState.value as SectionsUiState.Error).reason
        )

        shouldFail = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(sections, (viewModel.uiState.value as SectionsUiState.Content).sections)
    }
}
