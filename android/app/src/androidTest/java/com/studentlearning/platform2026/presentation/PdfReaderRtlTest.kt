package com.studentlearning.platform2026.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfSearchMatch
import com.studentlearning.platform2026.domain.model.PdfSearchResult
import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.studentlearning.platform2026.presentation.pdfreader.PdfReaderScreenContent
import com.studentlearning.platform2026.presentation.pdfreader.PdfReaderUiState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
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
}
