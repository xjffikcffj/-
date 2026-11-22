package com.studentlearning.platform2026.presentation.pdfreader

import androidx.lifecycle.SavedStateHandle
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
            assertEquals(1, progressRepo.getProgress("file-1", versionId = "v1")?.lastPageNumber)
        }

    @Test
    fun `restores previously saved reading page and clamps out of bounds saved page`() = runTest {
        val pdfRepo = FakePdfDocumentRepository(
            openHandler = { id, _ -> AppResult.Success(FakePdfDocumentSession(id, pageCount = 10)) }
        )
        val progressRepo = FakeReadingProgressRepository(
            initialProgress = mapOf(
                "file-saved::v1" to FileReadingProgress("file-saved", lastPageNumber = 7, totalPages = 10, 100L, versionId = "v1"),
                "file-over::v2" to FileReadingProgress("file-over", lastPageNumber = 40, totalPages = 40, 100L, versionId = "v2")
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
        assertEquals(10, progressRepo.getProgress("file-over", versionId = "v2")?.lastPageNumber)
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
        assertEquals(2, progressRepo.getProgress("file-nav", versionId = "v1")?.lastPageNumber)

        viewModel.goToPage(99)
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.currentPage)
        assertEquals(5, progressRepo.getProgress("file-nav", versionId = "v1")?.lastPageNumber)

        viewModel.onVisiblePageChanged(3)
        advanceTimeBy(350L)
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.currentPage)
        assertEquals(3, progressRepo.getProgress("file-nav", versionId = "v1")?.lastPageNumber)
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
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            fileAGate.await()
                        }
                        AppResult.Success(sessionA)
                    } else {
                        AppResult.Success(sessionB)
                    }
                }
            )
            val progressRepo = FakeReadingProgressRepository(
                initialProgress = mapOf(
                    "file-a::v1" to FileReadingProgress("file-a", 15, 20, 1L, versionId = "v1"),
                    "file-b::v1" to FileReadingProgress("file-b", 4, 6, 1L, versionId = "v1")
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

    @Test
    fun `overlapping debounced and immediate page saves preserve newest page without regression`() =
        runTest {
            val session = FakePdfDocumentSession(fileId = "file-race", pageCount = 20)
            val pdfRepo = FakePdfDocumentRepository(openHandler = { _, _ -> AppResult.Success(session) })
            val progressRepo = FakeReadingProgressRepository(saveDelayMs = 150L)

            val viewModel = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                initialFileId = "file-race",
                initialFileTitle = "ملف سباق التقدم",
                initialActiveVersionId = "v1"
            )
            advanceUntilIdle()

            // Trigger debounced scroll save to page 4, advance right to execution edge, then jump to page 11.
            viewModel.onVisiblePageChanged(4)
            advanceTimeBy(310L)
            viewModel.goToPage(11)
            advanceUntilIdle()

            assertEquals(11, viewModel.uiState.value.currentPage)
            assertEquals(11, progressRepo.getProgress("file-race", versionId = "v1")?.lastPageNumber)
            val raceSaves = progressRepo.recordedSaves.filter { it.first == "file-race" }.map { it.second }
            assertEquals(11, raceSaves.last())
        }

    @Test
    fun `pending debounced page save flushes on ViewModel clear and file switch without mixing files`() =
        runTest {
            val sessionA = FakePdfDocumentSession(fileId = "file-a", pageCount = 15)
            val sessionB = FakePdfDocumentSession(fileId = "file-b", pageCount = 9)
            val pdfRepo = FakePdfDocumentRepository(
                openHandler = { id, _ ->
                    if (id == "file-a") AppResult.Success(sessionA) else AppResult.Success(sessionB)
                }
            )
            val progressRepo = FakeReadingProgressRepository()
            val store = ViewModelStore()
            val factory = PdfReaderViewModel.provideFactory(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                fileId = "file-a",
                fileTitle = "ملف أ",
                activeVersionId = "v1"
            )
            val viewModel = ViewModelProvider(store, factory)[PdfReaderViewModel::class.java]
            advanceUntilIdle()

            // Scroll to page 7 and switch immediately before 300ms debounce fires.
            viewModel.onVisiblePageChanged(7)
            viewModel.openFile("file-b", "ملف ب", "v1")
            advanceUntilIdle()

            assertEquals(7, progressRepo.getProgress("file-a", versionId = "v1")?.lastPageNumber)
            assertEquals(1, progressRepo.getProgress("file-b", versionId = "v1")?.lastPageNumber)

            // Scroll file-b to page 5 and exit screen immediately before 300ms debounce fires.
            viewModel.onVisiblePageChanged(5)
            store.clear()
            advanceUntilIdle()

            assertEquals(5, progressRepo.getProgress("file-b", versionId = "v1")?.lastPageNumber)
            assertEquals(7, progressRepo.getProgress("file-a", versionId = "v1")?.lastPageNumber)
            assertTrue(sessionA.isClosed)
            assertTrue(sessionB.isClosed)
        }

    @Test
    fun `cancelling search mid-execution stops remaining page scans and resets search state`() =
        runTest {
            val sessionA = FakePdfDocumentSession(
                fileId = "file-long",
                pageCount = 10,
                pageTexts = mapOf(9 to "التكامل"),
                searchPageStepDelayMs = 100L
            )
            val sessionB = FakePdfDocumentSession(fileId = "file-next", pageCount = 4)
            val pdfRepo = FakePdfDocumentRepository(
                openHandler = { id, _ ->
                    if (id == "file-long") AppResult.Success(sessionA) else AppResult.Success(sessionB)
                }
            )
            val progressRepo = FakeReadingProgressRepository()
            val viewModel = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                initialFileId = "file-long",
                initialFileTitle = "ملف طويل",
                initialActiveVersionId = "v1"
            )
            advanceUntilIdle()

            viewModel.toggleSearchBar()
            viewModel.updateSearchQuery("التكامل")
            viewModel.executeSearch()
            advanceTimeBy(250L) // Stops after ~2-3 pages out of 10.
            assertEquals(PdfSearchStatus.SEARCHING, viewModel.uiState.value.searchResult.status)

            viewModel.clearSearch()
            advanceUntilIdle()

            assertEquals(PdfSearchStatus.IDLE, viewModel.uiState.value.searchResult.status)
            assertTrue(sessionA.searchCancelledCount >= 1)
            assertTrue(sessionA.searchPagesVisited < 10)

            // Switching files mid-search cancels search and closes old session without leaking state to file-next.
            viewModel.updateSearchQuery("التكامل")
            viewModel.executeSearch()
            advanceTimeBy(150L)
            viewModel.openFile("file-next", "ملف جديد", "v1")
            advanceUntilIdle()

            assertTrue(sessionA.isClosed)
            assertEquals("file-next", viewModel.uiState.value.fileId)
            assertEquals(PdfSearchStatus.IDLE, viewModel.uiState.value.searchResult.status)
        }

    @Test
    fun `zoom bounds clamp cleanly between 1x and 3x without negative or excessive scale`() =
        runTest {
            val session = FakePdfDocumentSession(fileId = "file-zoom", pageCount = 3)
            val viewModel = PdfReaderViewModel(
                pdfDocumentRepository = FakePdfDocumentRepository(
                    openHandler = { _, _ -> AppResult.Success(session) }
                ),
                readingProgressRepository = FakeReadingProgressRepository(),
                initialFileId = "file-zoom",
                initialFileTitle = "تكبير",
                initialActiveVersionId = "v1"
            )
            advanceUntilIdle()

            viewModel.setZoomScale(0.2f)
            assertEquals(PdfReaderUiState.MIN_ZOOM_SCALE, viewModel.uiState.value.zoomScale, 0.001f)
            assertFalse(viewModel.uiState.value.canZoomOut)

            viewModel.setZoomScale(10.0f)
            assertEquals(PdfReaderUiState.MAX_ZOOM_SCALE, viewModel.uiState.value.zoomScale, 0.001f)
            assertFalse(viewModel.uiState.value.canZoomIn)

            viewModel.resetZoom()
            assertEquals(PdfReaderUiState.MIN_ZOOM_SCALE, viewModel.uiState.value.zoomScale, 0.001f)
        }

    /**
     * Unit test verifying [PdfReaderViewModel] + [SavedStateHandle] state restoration by constructing
     * a second [PdfReaderViewModel] instance from saved key/value pairs.
     *
     * Limitation: Exercises [PdfReaderViewModel] and [SavedStateHandle] directly in JVM unit tests;
     * it does NOT perform full Android `Activity` / `NavBackStackEntry` process-death recreation.
     */
    @Test
    fun `surviving ViewModel keeps scroll session across rotation while process death restores SavedStateHandle with fresh session`() =
        runTest {
            val session1 = FakePdfDocumentSession(
                fileId = "file-proc",
                pageCount = 12,
                pageTexts = mapOf(
                    2 to "الفيزياء الحديثة",
                    9 to "الفيزياء النووية"
                )
            )
            val session2 = FakePdfDocumentSession(
                fileId = "file-proc",
                pageCount = 12,
                pageTexts = mapOf(
                    2 to "الفيزياء الحديثة",
                    9 to "الفيزياء النووية"
                )
            )
            val sessionOther = FakePdfDocumentSession(fileId = "file-other", pageCount = 6)

            var openAttempt = 0
            val pdfRepo = FakePdfDocumentRepository(
                openHandler = { id, _ ->
                    when (id) {
                        "file-proc" -> {
                            openAttempt++
                            AppResult.Success(if (openAttempt == 1) session1 else session2)
                        }
                        else -> AppResult.Success(sessionOther)
                    }
                }
            )
            val progressRepo = FakeReadingProgressRepository(
                initialProgress = mapOf(
                    "file-other::v2" to FileReadingProgress("file-other", 3, 6, 1L, versionId = "v2")
                )
            )
            val sharedSavedStateHandle = SavedStateHandle()

            // Process 1: user opens file-proc, jumps several times (token > 1), zooms, toggles night mode,
            // and scrolls manually to page 7 right before process death (before 300ms DataStore debounce flushes).
            val vmProcess1 = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                initialFileId = "file-proc",
                initialFileTitle = "الفيزياء",
                initialActiveVersionId = "v1",
                savedStateHandle = sharedSavedStateHandle
            )
            advanceUntilIdle()
            val sessionIdBeforeRotation = vmProcess1.uiState.value.scrollRequestSessionId

            vmProcess1.goToPage(3)
            vmProcess1.goToPage(5)
            vmProcess1.setZoomScale(2.0f)
            vmProcess1.toggleNightMode()
            vmProcess1.onVisiblePageChanged(7) // within 300ms debounce before DataStore write

            // Configuration change (rotation): same ViewModel survives with identical session ID & token.
            assertEquals(sessionIdBeforeRotation, vmProcess1.uiState.value.scrollRequestSessionId)
            assertEquals(7, vmProcess1.uiState.value.currentPage)
            assertEquals(2.0f, vmProcess1.uiState.value.zoomScale, 0.001f)
            val tokenBeforeDeath = vmProcess1.uiState.value.scrollRequestToken
            assertTrue(tokenBeforeDeath >= 3)

            // Simulate Process Death: old VM dies, new VM is instantiated with restored SavedStateHandle.
            val restoredMap = sharedSavedStateHandle.keys().associateWith { key ->
                sharedSavedStateHandle.get<Any?>(key)
            }
            val recreatedHandle = SavedStateHandle(restoredMap)
            val vmProcess2 = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                initialFileId = "file-proc",
                initialFileTitle = "الفيزياء",
                initialActiveVersionId = "v1",
                savedStateHandle = recreatedHandle
            )
            advanceUntilIdle()

            val restoredState = vmProcess2.uiState.value
            assertFalse(restoredState.isLoading)
            assertEquals(7, restoredState.currentPage)
            assertEquals(2.0f, restoredState.zoomScale, 0.001f)
            assertTrue(restoredState.isNightModeEnabled)
            assertTrue(
                "Recreated ViewModel must issue a fresh scrollRequestSessionId",
                restoredState.scrollRequestSessionId != sessionIdBeforeRotation
            )
            assertEquals(7, progressRepo.getProgress("file-proc", versionId = "v1")?.lastPageNumber)

            // Immediately after restoration: Next, Previous, Jump, and Search must all advance tokens cleanly.
            vmProcess2.nextPage()
            advanceUntilIdle()
            assertEquals(8, vmProcess2.uiState.value.currentPage)
            assertEquals(1, vmProcess2.uiState.value.scrollRequestToken)

            vmProcess2.previousPage()
            advanceUntilIdle()
            assertEquals(7, vmProcess2.uiState.value.currentPage)
            assertEquals(2, vmProcess2.uiState.value.scrollRequestToken)

            vmProcess2.goToPage(11)
            advanceUntilIdle()
            assertEquals(11, vmProcess2.uiState.value.currentPage)
            assertEquals(11, vmProcess2.uiState.value.scrollTargetPage)
            assertEquals(3, vmProcess2.uiState.value.scrollRequestToken)

            vmProcess2.toggleSearchBar()
            vmProcess2.updateSearchQuery("الفيزياء")
            vmProcess2.executeSearch()
            advanceUntilIdle()
            assertEquals(PdfSearchStatus.FOUND, vmProcess2.uiState.value.searchResult.status)
            assertEquals(2, vmProcess2.uiState.value.currentPage)
            vmProcess2.nextSearchMatch()
            advanceUntilIdle()
            assertEquals(9, vmProcess2.uiState.value.currentPage)
            assertEquals(5, vmProcess2.uiState.value.scrollRequestToken)

            // Opening a different file after restoration resets zoom/search and uses that file's progress.
            vmProcess2.openFile("file-other", "ملف آخر", "v2")
            advanceUntilIdle()
            assertEquals("file-other", vmProcess2.uiState.value.fileId)
            assertEquals(3, vmProcess2.uiState.value.currentPage)
            assertEquals(1.0f, vmProcess2.uiState.value.zoomScale, 0.001f)
            assertFalse(vmProcess2.uiState.value.isSearchBarVisible)
        }

    @Test
    fun `isolates reading progress across versions of same file and preserves versionLabel`() =
        runTest {
            val sessionV1 = FakePdfDocumentSession(fileId = "doc-multi", pageCount = 12)
            val sessionV2 = FakePdfDocumentSession(fileId = "doc-multi", pageCount = 18)
            val pdfRepo = FakePdfDocumentRepository(
                openHandler = { _, versionId ->
                    if (versionId == "v1") AppResult.Success(sessionV1) else AppResult.Success(sessionV2)
                }
            )
            val progressRepo = FakeReadingProgressRepository()
            val savedHandle = SavedStateHandle()

            val viewModel = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                initialFileId = "doc-multi",
                initialFileTitle = "ملزمة الكيمياء",
                initialActiveVersionId = "v1",
                savedStateHandle = savedHandle,
                initialVersionLabel = "1.0"
            )
            advanceUntilIdle()

            assertEquals("1.0", viewModel.uiState.value.versionLabel)
            viewModel.onVisiblePageChanged(6) // Pending debounced save for v1.

            // Switching to v2 flushes v1 progress (page 6) and opens v2 independently at page 1.
            viewModel.openFile(
                fileId = "doc-multi",
                fileTitle = "ملزمة الكيمياء",
                activeVersionId = "v2",
                versionLabel = "2.0"
            )
            advanceUntilIdle()

            assertTrue(sessionV1.isClosed)
            assertEquals("v2", viewModel.uiState.value.activeVersionId)
            assertEquals("2.0", viewModel.uiState.value.versionLabel)
            assertEquals(1, viewModel.uiState.value.currentPage)
            assertEquals(6, progressRepo.getProgress("doc-multi", versionId = "v1")?.lastPageNumber)

            viewModel.goToPage(14)
            advanceUntilIdle()
            assertEquals(14, progressRepo.getProgress("doc-multi", versionId = "v2")?.lastPageNumber)
            assertEquals(6, progressRepo.getProgress("doc-multi", versionId = "v1")?.lastPageNumber)

            // Recreate from SavedStateHandle with null versionLabel param -> restores saved "2.0".
            val recreated = PdfReaderViewModel(
                pdfDocumentRepository = pdfRepo,
                readingProgressRepository = progressRepo,
                initialFileId = "doc-multi",
                initialFileTitle = "ملزمة الكيمياء",
                initialActiveVersionId = "v2",
                savedStateHandle = SavedStateHandle(savedHandle.keys().associateWith { savedHandle.get<Any?>(it) }),
                initialVersionLabel = null
            )
            advanceUntilIdle()
            assertEquals(14, recreated.uiState.value.currentPage)
            assertEquals("2.0", recreated.uiState.value.versionLabel)
        }
}
