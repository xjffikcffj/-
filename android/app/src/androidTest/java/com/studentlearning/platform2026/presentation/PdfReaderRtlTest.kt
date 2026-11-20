package com.studentlearning.platform2026.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfSearchMatch
import com.studentlearning.platform2026.domain.model.PdfSearchResult
import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.studentlearning.platform2026.presentation.pdfreader.PDF_VIEWPORT_TEST_TAG
import com.studentlearning.platform2026.presentation.pdfreader.PdfReaderScreenContent
import com.studentlearning.platform2026.presentation.pdfreader.PdfReaderUiState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfReaderRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun fakeUiSession(fileId: String = "f1", pageCount: Int = 10): PdfDocumentSession {
        return object : PdfDocumentSession {
            override val fileId: String = fileId
            override val pageCount: Int = pageCount
            override suspend fun getPageAspectRatio(pageIndexZeroBased: Int): Float = 0.707f
            override suspend fun searchText(query: String): PdfSearchResult =
                PdfSearchResult(query = query, status = PdfSearchStatus.NO_RESULTS)
            override fun close() = Unit
        }
    }

    @Test
    fun pdfReader_displaysArabicControls_searchFeedback_andNightToggle() {
        var nightToggled = false
        var pageJumpClicked = false

        val uiState = PdfReaderUiState(
            fileId = "file-1",
            fileTitle = "ملزمة الكيمياء - الفصل الأول",
            activeVersionId = "v1",
            isLoading = false,
            documentSession = fakeUiSession(),
            totalPages = 10,
            currentPage = 3,
            zoomScale = 1.25f,
            isSearchBarVisible = true,
            searchQuery = "المعادلة",
            searchResult = PdfSearchResult(
                query = "المعادلة",
                status = PdfSearchStatus.FOUND,
                matches = listOf(PdfSearchMatch(pageNumber = 3, matchIndexInDocument = 0, snippet = "المعادلة الكيميائية"))
            ),
            activeMatchCursor = 0
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                PdfReaderScreenContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onRetry = {},
                    onVisiblePageChanged = {},
                    onPreviousPage = {},
                    onNextPage = {},
                    onOpenPageJump = { pageJumpClicked = true },
                    onDismissPageJump = {},
                    onJumpToPage = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onResetZoom = {},
                    onSetZoomScale = {},
                    onToggleNightMode = { nightToggled = true },
                    onToggleControls = {},
                    onToggleSearch = {},
                    onUpdateSearchQuery = {},
                    onExecuteSearch = {},
                    onClearSearch = {},
                    onNextMatch = {},
                    onPreviousMatch = {}
                )
            }
        }

        composeTestRule.onNodeWithText("ملزمة الكيمياء - الفصل الأول").assertIsDisplayed()
        composeTestRule.onNodeWithText("صفحة 3 من 10").assertIsDisplayed().performClick()
        assertTrue(pageJumpClicked)

        composeTestRule.onNodeWithText("125%").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 من 1 (صفحة 3)").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("الوضع الليلي للقراءة").performClick()
        assertTrue(nightToggled)
    }

    @Test
    fun pdfReader_displaysScannedPdfNoExtractableTextWarning() {
        val uiState = PdfReaderUiState(
            fileId = "file-scan",
            fileTitle = "دفتر مصوّر",
            activeVersionId = "v1",
            isLoading = false,
            documentSession = fakeUiSession("file-scan", 4),
            totalPages = 4,
            currentPage = 1,
            isSearchBarVisible = true,
            searchQuery = "نص",
            searchResult = PdfSearchResult(
                query = "نص",
                status = PdfSearchStatus.NO_EXTRACTABLE_TEXT
            )
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                PdfReaderScreenContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onRetry = {},
                    onVisiblePageChanged = {},
                    onPreviousPage = {},
                    onNextPage = {},
                    onOpenPageJump = {},
                    onDismissPageJump = {},
                    onJumpToPage = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onResetZoom = {},
                    onSetZoomScale = {},
                    onToggleNightMode = {},
                    onToggleControls = {},
                    onToggleSearch = {},
                    onUpdateSearchQuery = {},
                    onExecuteSearch = {},
                    onClearSearch = {},
                    onNextMatch = {},
                    onPreviousMatch = {}
                )
            }
        }

        composeTestRule.onNodeWithText(
            "هذا الملف لا يحتوي على نص قابل للاستخراج (قد تكون الصفحات مصوّرة)، لذا لا يتوفر البحث النصي له حالياً."
        ).assertIsDisplayed()
    }

    @Test
    fun pdfReader_displaysClearArabicErrorAndRetryAction() {
        var retried = false
        val uiState = PdfReaderUiState(
            fileId = "file-unconfigured",
            fileTitle = "ملف",
            activeVersionId = "v1",
            isLoading = false,
            errorReason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                PdfReaderScreenContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onRetry = { retried = true },
                    onVisiblePageChanged = {},
                    onPreviousPage = {},
                    onNextPage = {},
                    onOpenPageJump = {},
                    onDismissPageJump = {},
                    onJumpToPage = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onResetZoom = {},
                    onSetZoomScale = {},
                    onToggleNightMode = {},
                    onToggleControls = {},
                    onToggleSearch = {},
                    onUpdateSearchQuery = {},
                    onExecuteSearch = {},
                    onClearSearch = {},
                    onNextMatch = {},
                    onPreviousMatch = {}
                )
            }
        }

        composeTestRule.onNodeWithText("تعذّر فتح ملف PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("إعادة المحاولة").performClick()
        assertTrue(retried)
    }

    @Test
    fun pdfReader_twoFingerPinchInAt1xDoesNotScroll_andJumpingWhileZoomedAlignsTargetPage() {
        var visiblePage by mutableStateOf(1)
        var uiState by mutableStateOf(
            PdfReaderUiState(
                fileId = "file-gestures",
                fileTitle = "الرياضيات - التفاضل",
                activeVersionId = "v1",
                isLoading = false,
                documentSession = fakeUiSession("file-gestures", 10),
                totalPages = 10,
                currentPage = 1,
                scrollTargetPage = 1,
                scrollRequestToken = 1,
                zoomScale = 1.0f
            )
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                PdfReaderScreenContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onRetry = {},
                    onVisiblePageChanged = { newPage ->
                        visiblePage = newPage
                        uiState = uiState.copy(currentPage = newPage)
                    },
                    onPreviousPage = {},
                    onNextPage = {
                        val next = (uiState.currentPage + 1).coerceAtMost(uiState.totalPages)
                        uiState = uiState.copy(
                            currentPage = next,
                            scrollTargetPage = next,
                            scrollRequestToken = uiState.scrollRequestToken + 1
                        )
                    },
                    onOpenPageJump = {},
                    onDismissPageJump = {},
                    onJumpToPage = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onResetZoom = {},
                    onSetZoomScale = { scale -> uiState = uiState.copy(zoomScale = scale) },
                    onToggleNightMode = {},
                    onToggleControls = {},
                    onToggleSearch = {},
                    onUpdateSearchQuery = {},
                    onExecuteSearch = {},
                    onClearSearch = {},
                    onNextMatch = {},
                    onPreviousMatch = {}
                )
            }
        }

        // Two-finger pinch-in on the PDF viewport itself at 1x zoom must keep page at 1.
        val viewportNode = composeTestRule.onNodeWithTag(PDF_VIEWPORT_TEST_TAG)
        viewportNode.performTouchInput {
            pinch(
                start0 = Offset(centerX - 180f, centerY),
                end0 = Offset(centerX - 30f, centerY - 200f),
                start1 = Offset(centerX + 180f, centerY),
                end1 = Offset(centerX + 30f, centerY - 200f)
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(1, visiblePage)
        composeTestRule.onNodeWithText("جاري تحميل الصفحة 1…").assertIsDisplayed()

        // Two-finger pinch-out on the PDF viewport itself must actually zoom in (> 1.2x) without scrolling pages.
        viewportNode.performTouchInput {
            pinch(
                start0 = Offset(centerX - 40f, centerY),
                end0 = Offset(centerX - 220f, centerY),
                start1 = Offset(centerX + 40f, centerY),
                end1 = Offset(centerX + 220f, centerY)
            )
        }
        composeTestRule.waitForIdle()
        assertTrue(
            "Expected two-finger pinch-out on viewport to increase zoomScale above 1.2f, was ${uiState.zoomScale}",
            uiState.zoomScale > 1.2f
        )
        assertEquals(1, visiblePage)

        // Reset zoom back to 1.0x before single-finger swipe test.
        uiState = uiState.copy(zoomScale = 1.0f)
        composeTestRule.waitForIdle()

        // Single-finger swipe up on the PDF viewport itself at 1x must advance beyond page 1.
        repeat(3) {
            viewportNode.performTouchInput {
                swipeUp()
            }
            composeTestRule.waitForIdle()
        }
        assertTrue(
            "Expected single-finger swipe on viewport to scroll past page 1, got $visiblePage",
            visiblePage > 1
        )
        val pageAfterSwipe = visiblePage

        // Zoom in, then jump to next page -> target page item and indicator update cleanly.
        uiState = uiState.copy(zoomScale = 2.0f)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("الصفحة التالية").performClick()
        composeTestRule.waitForIdle()
        val expectedAfterJump = (pageAfterSwipe + 1).coerceAtMost(10)
        assertEquals(expectedAfterJump, uiState.currentPage)
        composeTestRule.onNodeWithText("200%").assertIsDisplayed()
        composeTestRule.onNodeWithText("صفحة $expectedAfterJump من 10").assertIsDisplayed()
        composeTestRule.onNodeWithText("جاري تحميل الصفحة $expectedAfterJump…").assertIsDisplayed()
    }

    @Test
    fun pdfReader_preservesScrolledPageAndZoomAfterConfigurationRecreation() {
        val restorationTester = StateRestorationTester(composeTestRule)
        var visiblePage by mutableStateOf(1)
        var uiState by mutableStateOf(
            PdfReaderUiState(
                fileId = "file-rotate",
                fileTitle = "الفيزياء - الموجات",
                activeVersionId = "v1",
                isLoading = false,
                documentSession = fakeUiSession("file-rotate", 10),
                totalPages = 10,
                currentPage = 1,
                scrollTargetPage = 1,
                scrollRequestToken = 1,
                zoomScale = 1.0f
            )
        )

        restorationTester.setContent {
            HaqibatiTheme {
                PdfReaderScreenContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onRetry = {},
                    onVisiblePageChanged = { newPage ->
                        visiblePage = newPage
                        uiState = uiState.copy(currentPage = newPage)
                    },
                    onPreviousPage = {},
                    onNextPage = {},
                    onOpenPageJump = {},
                    onDismissPageJump = {},
                    onJumpToPage = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onResetZoom = {},
                    onSetZoomScale = { scale -> uiState = uiState.copy(zoomScale = scale) },
                    onToggleNightMode = {},
                    onToggleControls = {},
                    onToggleSearch = {},
                    onUpdateSearchQuery = {},
                    onExecuteSearch = {},
                    onClearSearch = {},
                    onNextMatch = {},
                    onPreviousMatch = {}
                )
            }
        }

        val viewportNode = composeTestRule.onNodeWithTag(PDF_VIEWPORT_TEST_TAG)
        repeat(3) {
            viewportNode.performTouchInput {
                swipeUp()
            }
            composeTestRule.waitForIdle()
        }
        assertTrue(
            "Precondition failed: user swipe should move past page 1, got $visiblePage",
            visiblePage > 1
        )
        val pageBeforeRotation = visiblePage
        uiState = uiState.copy(zoomScale = 2.0f)
        composeTestRule.waitForIdle()

        // Simulate Android configuration change (screen rotation) while scrollTargetPage is still 1
        // and scrollRequestToken stayed 1: reading position must remain at pageBeforeRotation.
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertEquals(pageBeforeRotation, visiblePage)
        assertEquals(pageBeforeRotation, uiState.currentPage)
        composeTestRule.onNodeWithText("صفحة $pageBeforeRotation من 10").assertIsDisplayed()
        composeTestRule.onNodeWithText("200%").assertIsDisplayed()
        composeTestRule.onNodeWithText("جاري تحميل الصفحة $pageBeforeRotation…").assertIsDisplayed()
    }

    /**
     * Verifies Compose-layer state restoration (`rememberSaveable` for `lastHandledScrollRequestToken`
     * and `lastHandledScrollSessionId`) on [PdfReaderScreenContent] using [StateRestorationTester]
     * while supplying synthetic [PdfReaderUiState] instances directly.
     *
     * Limitation: This test does NOT instantiate [PdfReaderViewModel], invoke `provideFactory`,
     * or recreate an Android `Activity` / `SavedStateHandle`; ViewModel + `SavedStateHandle`
     * restoration is tested separately in `PdfReaderViewModelTest`, and full Activity/process
     * death integration still requires an Android instrumentation/device run.
     */
    @Test
    fun pdfReader_afterProcessDeathRecreation_executesJumpsNextPrevSearchAndNewFileDespiteHigherPriorToken() {
        val restorationTester = StateRestorationTester(composeTestRule)
        var visiblePage by mutableStateOf(1)
        var uiState by mutableStateOf(
            PdfReaderUiState(
                fileId = "file-kill",
                fileTitle = "الكيمياء",
                activeVersionId = "v1",
                isLoading = false,
                documentSession = fakeUiSession("file-kill", 10),
                totalPages = 10,
                currentPage = 1,
                scrollTargetPage = 1,
                scrollRequestSessionId = "vm-process-1",
                scrollRequestToken = 1,
                zoomScale = 1.0f
            )
        )

        val requestJump: (Int) -> Unit = { target ->
            val clamped = target.coerceIn(1, uiState.totalPages.coerceAtLeast(1))
            uiState = uiState.copy(
                currentPage = clamped,
                scrollTargetPage = clamped,
                scrollRequestToken = uiState.scrollRequestToken + 1
            )
        }

        restorationTester.setContent {
            HaqibatiTheme {
                PdfReaderScreenContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onRetry = {},
                    onVisiblePageChanged = { newPage ->
                        visiblePage = newPage
                        uiState = uiState.copy(currentPage = newPage)
                    },
                    onPreviousPage = {
                        if (uiState.canNavigatePreviousPage) requestJump(uiState.currentPage - 1)
                    },
                    onNextPage = {
                        if (uiState.canNavigateNextPage) requestJump(uiState.currentPage + 1)
                    },
                    onOpenPageJump = {},
                    onDismissPageJump = {},
                    onJumpToPage = requestJump,
                    onZoomIn = {},
                    onZoomOut = {},
                    onResetZoom = {},
                    onSetZoomScale = { scale -> uiState = uiState.copy(zoomScale = scale) },
                    onToggleNightMode = {},
                    onToggleControls = {},
                    onToggleSearch = {},
                    onUpdateSearchQuery = {},
                    onExecuteSearch = {},
                    onClearSearch = {},
                    onNextMatch = {
                        val matches = uiState.searchResult.matches
                        if (matches.isNotEmpty()) {
                            val nextCursor = (uiState.activeMatchCursor + 1) % matches.size
                            uiState = uiState.copy(activeMatchCursor = nextCursor)
                            requestJump(matches[nextCursor].pageNumber)
                        }
                    },
                    onPreviousMatch = {}
                )
            }
        }

        // Advance token in process 1 up to token = 5 (Bundle records lastHandledScrollRequestToken = 5).
        repeat(4) {
            composeTestRule.onNodeWithContentDescription("الصفحة التالية").performClick()
            composeTestRule.waitForIdle()
        }
        assertEquals(5, uiState.scrollRequestToken)
        assertEquals(5, visiblePage)

        // Simulate Process Death: Compose restores Bundle (with old lastHandledScrollRequestToken = 5),
        // while newly recreated ViewModel starts with fresh scrollRequestSessionId = "vm-process-2"
        // and scrollRequestToken = 0 (restored at page 5 via SavedStateHandle).
        restorationTester.emulateSavedInstanceStateRestore()
        uiState = PdfReaderUiState(
            fileId = "file-kill",
            fileTitle = "الكيمياء",
            activeVersionId = "v1",
            isLoading = false,
            documentSession = fakeUiSession("file-kill", 10),
            totalPages = 10,
            currentPage = 5,
            scrollTargetPage = 5,
            scrollRequestSessionId = "vm-process-2",
            scrollRequestToken = 0,
            zoomScale = 1.5f,
            isSearchBarVisible = true,
            searchQuery = "المعادلة",
            searchResult = PdfSearchResult(
                query = "المعادلة",
                status = PdfSearchStatus.FOUND,
                matches = listOf(
                    PdfSearchMatch(pageNumber = 2, matchIndexInDocument = 0, snippet = "أول"),
                    PdfSearchMatch(pageNumber = 9, matchIndexInDocument = 1, snippet = "ثاني")
                )
            ),
            activeMatchCursor = 0
        )
        composeTestRule.waitForIdle()
        assertEquals(5, visiblePage)

        // Token 1 (< old 5): Next Page must NOT be ignored -> scrolls to page 6.
        composeTestRule.onNodeWithContentDescription("الصفحة التالية").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, uiState.scrollRequestToken)
        assertEquals(6, visiblePage)
        composeTestRule.onNodeWithText("جاري تحميل الصفحة 6…").assertIsDisplayed()

        // Token 2 (< old 5): Previous Page -> scrolls back to page 5.
        composeTestRule.onNodeWithContentDescription("الصفحة السابقة").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, uiState.scrollRequestToken)
        assertEquals(5, visiblePage)
        composeTestRule.onNodeWithText("جاري تحميل الصفحة 5…").assertIsDisplayed()

        // Token 3 (< old 5): Direct Jump -> scrolls to page 8.
        requestJump(8)
        composeTestRule.waitForIdle()
        assertEquals(3, uiState.scrollRequestToken)
        assertEquals(8, visiblePage)
        composeTestRule.onNodeWithText("جاري تحميل الصفحة 8…").assertIsDisplayed()

        // Token 4 (< old 5): Search Next Match -> scrolls to page 9.
        composeTestRule.onNodeWithContentDescription("النتيجة التالية").performClick()
        composeTestRule.waitForIdle()
        assertEquals(4, uiState.scrollRequestToken)
        assertEquals(9, visiblePage)
        composeTestRule.onNodeWithText("جاري تحميل الصفحة 9…").assertIsDisplayed()

        // Opening a new file after restoration resets viewport cleanly to the new file's target page.
        uiState = PdfReaderUiState(
            fileId = "file-new-after-kill",
            fileTitle = "الأحياء",
            activeVersionId = "v2",
            isLoading = false,
            documentSession = fakeUiSession("file-new-after-kill", 4),
            totalPages = 4,
            currentPage = 2,
            scrollTargetPage = 2,
            scrollRequestSessionId = "vm-process-2",
            scrollRequestToken = 1,
            zoomScale = 1.0f
        )
        composeTestRule.waitForIdle()
        assertEquals(2, visiblePage)
        composeTestRule.onNodeWithText("صفحة 2 من 4").assertIsDisplayed()
        composeTestRule.onNodeWithText("جاري تحميل الصفحة 2…").assertIsDisplayed()
    }
}
