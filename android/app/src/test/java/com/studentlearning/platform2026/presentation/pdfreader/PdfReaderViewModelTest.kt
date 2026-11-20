package com.studentlearning.platform2026.presentation.pdfreader

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.FileReadingProgress
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import com.studentlearning.platform2026.fakes.FakePdfDocumentRepository
import com.studentlearning.platform2026.fakes.FakePdfDocumentSession
import com.studentlearning.platform2026.fakes.FakeReadingProgressRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfReaderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `opens readable document without authentication and starts at page 1 when no progress exists`() =
        runTest {
            val session = FakePdfDocumentSession(fileId = "file-1", pageCount = 12)
            val pdfRepo = FakePdfDocumentRepository(
                openHandler = { _, _ -> AppResult.Success(session) }
            )
            val progressRepo = FakeReadingProgressRepository()

            val viewModel = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                initialFileId = "file-1",
                initialFileTitle = "ملخص الفيزياء",
                initialActiveVersionId = "v1"
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.errorReason)
            assertEquals(12, state.totalPages)
            assertEquals(1, state.currentPage)
            assertNull(state.resumedFromSavedPage)
            assertEquals(1, progressRepo.getProgress("file-1")?.lastPageNumber)
        }

    @Test
    fun `restores previously saved reading page and clamps out of bounds saved page`() = runTest {
        val pdfRepo = FakePdfDocumentRepository(
            openHandler = { id, _ -> AppResult.Success(FakePdfDocumentSession(id, pageCount = 10)) }
        )
        val progressRepo = FakeReadingProgressRepository(
            initialProgress = mapOf(
                "file-saved" to FileReadingProgress("file-saved", lastPageNumber = 7, totalPages = 10, 100L),
                "file-over" to FileReadingProgress("file-over", lastPageNumber = 40, totalPages = 40, 100L)
            )
        )

        val vmSaved = PdfReaderViewModel(
            pdfDocumentRepository = pdfRepo,
            readingProgressRepository = progressRepo,
            initialFileId = "file-saved",
            initialFileTitle = "ملزمة",
            initialActiveVersionId = "v1"
        )
        advanceUntilIdle()
        assertEquals(7, vmSaved.uiState.value.currentPage)
        assertEquals(7, vmSaved.uiState.value.resumedFromSavedPage)

        val vmClamped = PdfReaderViewModel(
            pdfDocumentRepository = pdfRepo,
            readingProgressRepository = progressRepo,
            initialFileId = "file-over",
            initialFileTitle = "ملزمة محدثة",
            initialActiveVersionId = "v2"
        )
        advanceUntilIdle()
        assertEquals(10, vmClamped.uiState.value.currentPage)
        assertEquals(10, progressRepo.getProgress("file-over")?.lastPageNumber)
    }

    @Test
    fun `navigates pages within bounds and saves updated page locally`() = runTest {
        val pdfRepo = FakePdfDocumentRepository(
            openHandler = { id, _ -> AppResult.Success(FakePdfDocumentSession(id, pageCount = 5)) }
        )
        val progressRepo = FakeReadingProgressRepository()
        val viewModel = PdfReaderViewModel(
            pdfDocumentRepository = pdfRepo,
            readingProgressRepository = progressRepo,
            initialFileId = "file-nav",
            initialFileTitle = "قراءة",
            initialActiveVersionId = "v1"
        )
        advanceUntilIdle()

        viewModel.previousPage()
        assertEquals(1, viewModel.uiState.value.currentPage)

        viewModel.nextPage()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.currentPage)
        assertEquals(2, progressRepo.getProgress("file-nav")?.lastPageNumber)

        viewModel.goToPage(99)
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.currentPage)
        assertEquals(5, progressRepo.getProgress("file-nav")?.lastPageNumber)

        viewModel.onVisiblePageChanged(3)
        advanceTimeBy(350L)
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.currentPage)
        assertEquals(3, progressRepo.getProgress("file-nav")?.lastPageNumber)
    }

    @Test
    fun `surfaces corrupted or network errors cleanly and recovers on retry`() = runTest {
        var failWith: PdfFailureReason? = PdfFailureReason.NETWORK_UNAVAILABLE
        val pdfRepo = FakePdfDocumentRepository(
            openHandler = { id, _ ->
                val failure = failWith
                if (failure != null) {
                    AppResult.Error("Error", PdfLoadException(failure))
                } else {
                    AppResult.Success(FakePdfDocumentSession(id, pageCount = 8))
                }
            }
        )
        val progressRepo = FakeReadingProgressRepository()

        val viewModel = PdfReaderViewModel(
            pdfDocumentRepository = pdfRepo,
            readingProgressRepository = progressRepo,
            initialFileId = "file-err",
            initialFileTitle = "ملف",
            initialActiveVersionId = "v1"
        )
        advanceUntilIdle()
        assertEquals(PdfFailureReason.NETWORK_UNAVAILABLE, viewModel.uiState.value.errorReason)

        failWith = PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE, viewModel.uiState.value.errorReason)

        failWith = null
        viewModel.retry()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.errorReason)
        assertEquals(8, viewModel.uiState.value.totalPages)
    }

    @Test
    fun `rapid file switching closes previous session and discards stale open completions`() =
        runTest {
            val fileAGate = CompletableDeferred<Unit>()
            val sessionA = FakePdfDocumentSession(fileId = "file-a", pageCount = 20)
            val sessionB = FakePdfDocumentSession(fileId = "file-b", pageCount = 6)

            val pdfRepo = FakePdfDocumentRepository(
                openHandler = { id, _ ->
                    if (id == "file-a") {
                        fileAGate.await()
                        AppResult.Success(sessionA)
                    } else {
                        AppResult.Success(sessionB)
                    }
                }
            )
            val progressRepo = FakeReadingProgressRepository(
                initialProgress = mapOf(
                    "file-a" to FileReadingProgress("file-a", 15, 20, 1L),
                    "file-b" to FileReadingProgress("file-b", 4, 6, 1L)
                )
            )

            val viewModel = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                autoOpen = false
            )

            // Start opening A, then rapidly switch to B before A resolves.
            viewModel.openFile("file-a", "ملف أ", "v1")
            viewModel.openFile("file-b", "ملف ب", "v1")
            advanceUntilIdle()

            assertEquals("file-b", viewModel.uiState.value.fileId)
            assertEquals(4, viewModel.uiState.value.currentPage)
            assertEquals(6, viewModel.uiState.value.totalPages)

            // Even if stale open for A finishes afterwards, it must be closed immediately and ignored.
            fileAGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(sessionA.isClosed)
            assertFalse(sessionB.isClosed)
            assertEquals("file-b", viewModel.uiState.value.fileId)
            assertEquals(4, viewModel.uiState.value.currentPage)

            // Switching again to C closes session B cleanly and resets zoom/search.
            viewModel.setZoomScale(2.0f)
            val sessionC = FakePdfDocumentSession(fileId = "file-c", pageCount = 3)
            pdfRepo.openHandler = { _, _ -> AppResult.Success(sessionC) }
            viewModel.openFile("file-c", "ملف ج", "v1")
            advanceUntilIdle()

            assertTrue(sessionB.isClosed)
            assertEquals(1.0f, viewModel.uiState.value.zoomScale, 0.001f)
            assertEquals(1, viewModel.uiState.value.currentPage)
        }

    @Test
    fun `search jumps to matched pages and night mode or zoom do not reload document`() = runTest {
        val session = FakePdfDocumentSession(
            fileId = "file-search",
            pageCount = 5,
            pageTexts = mapOf(
                1 to "مقدمة عامة",
                3 to "التكامل غير المحدد",
                5 to "أمثلة التكامل المحدود"
            )
        )
        val pdfRepo = FakePdfDocumentRepository(
            openHandler = { _, _ -> AppResult.Success(session) }
        )
        val progressRepo = FakeReadingProgressRepository()

        val viewModel = PdfReaderViewModel(
            pdfDocumentRepository = pdfRepo,
            readingProgressRepository = progressRepo,
            initialFileId = "file-search",
            initialFileTitle = "الرياضيات",
            initialActiveVersionId = "v1"
        )
        advanceUntilIdle()
        assertEquals(1, pdfRepo.openCalls)

        viewModel.toggleNightMode()
        viewModel.zoomIn()
        assertTrue(viewModel.uiState.value.isNightModeEnabled)
        assertEquals(1.25f, viewModel.uiState.value.zoomScale, 0.001f)
        assertEquals(1, pdfRepo.openCalls)

        viewModel.toggleSearchBar()
        viewModel.updateSearchQuery("التكامل")
        viewModel.executeSearch()
        advanceUntilIdle()

        assertEquals(PdfSearchStatus.FOUND, viewModel.uiState.value.searchResult.status)
        assertEquals(2, viewModel.uiState.value.searchResult.matches.size)
        assertEquals(0, viewModel.uiState.value.activeMatchCursor)
        assertEquals(3, viewModel.uiState.value.currentPage)

        viewModel.nextSearchMatch()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.activeMatchCursor)
        assertEquals(5, viewModel.uiState.value.currentPage)

        viewModel.previousSearchMatch()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.activeMatchCursor)
        assertEquals(3, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `closing ViewModelStore invokes onCleared and closes active PDF session`() = runTest {
        val session = FakePdfDocumentSession(fileId = "file-lifecycle", pageCount = 4)
        val pdfRepo = FakePdfDocumentRepository(
            openHandler = { _, _ -> AppResult.Success(session) }
        )
        val progressRepo = FakeReadingProgressRepository()
        val store = ViewModelStore()
        val factory: ViewModelProvider.Factory = PdfReaderViewModel.provideFactory(
            pdfDocumentRepository = pdfRepo,
            readingProgressRepository = progressRepo,
            fileId = "file-lifecycle",
            fileTitle = "اختبار الموارد",
            activeVersionId = "v1"
        )
        val viewModel = ViewModelProvider(store, factory)[PdfReaderViewModel::class.java]
        advanceUntilIdle()

        assertFalse(session.isClosed)
        store.clear()

        assertTrue(session.isClosed)
        assertNull(viewModel.uiState.value.documentSession)
    }
}
