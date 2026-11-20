package com.studentlearning.platform2026.presentation.library

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
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
class SectionFilesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `transitions to Empty when section has no published files`() = runTest {
        val repository = FakeCatalogRepository(
            studyFilesResult = { AppResult.Success(emptyList()) }
        )

        val viewModel = SectionFilesViewModel("sec-1", repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SectionFilesUiState.Empty)
    }

    @Test
    fun `loads published study files and retries cleanly after failure`() = runTest {
        var shouldFail = true
        val files = listOf(
            StudyFileMetadata(
                id = "file-1",
                sectionId = "sec-1",
                title = "ملخص الفصل الأول",
                description = "نسخة منقحة",
                order = 1,
                isPublished = true,
                isArchived = false,
                activeVersionId = "v1"
            )
        )
        val repository = FakeCatalogRepository(
            studyFilesResult = {
                if (shouldFail) {
                    AppResult.Error(
                        message = "Unavailable",
                        cause = CatalogException(CatalogFailureReason.NETWORK_OR_UNAVAILABLE)
                    )
                } else {
                    AppResult.Success(files)
                }
            }
        )

        val viewModel = SectionFilesViewModel("sec-1", repository)
        advanceUntilIdle()

        assertEquals(
            CatalogFailureReason.NETWORK_OR_UNAVAILABLE,
            (viewModel.uiState.value as SectionFilesUiState.Error).reason
        )

        shouldFail = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(files, (viewModel.uiState.value as SectionFilesUiState.Content).files)
    }
}
