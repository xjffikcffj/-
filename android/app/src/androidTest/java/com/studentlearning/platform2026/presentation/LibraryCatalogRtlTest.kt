package com.studentlearning.platform2026.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.presentation.library.LibraryScreenContent
import com.studentlearning.platform2026.presentation.library.LibraryUiState
import com.studentlearning.platform2026.presentation.library.SectionsScreenContent
import com.studentlearning.platform2026.presentation.library.SectionsUiState
import com.studentlearning.platform2026.presentation.library.SubjectsScreenContent
import com.studentlearning.platform2026.presentation.library.SubjectsUiState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryCatalogRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun libraryScreen_displaysArabicBranchesAndTriggersSelection() {
        var selectedBranch: StudyBranch? = null
        val branches = listOf(
            StudyBranch("scientific", "السادس العلمي", 1, true),
            StudyBranch("literary", "السادس الادبي", 2, true)
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                LibraryScreenContent(
                    uiState = LibraryUiState.Content(branches),
                    onSelectBranch = { selectedBranch = it },
                    onRetry = {}
                )
            }
        }

        composeTestRule.onNodeWithText("السادس العلمي").assertIsDisplayed().performClick()
        assertEquals("scientific", selectedBranch?.id)
    }

    @Test
    fun subjectsScreen_displaysErrorAndInvokesRetry() {
        var retried = false

        composeTestRule.setContent {
            HaqibatiTheme {
                SubjectsScreenContent(
                    uiState = SubjectsUiState.Error(CatalogFailureReason.PERMISSION_DENIED),
                    onSelectSubject = {},
                    onRetry = { retried = true }
                )
            }
        }

        composeTestRule.onNodeWithText("تعذّر تحميل البيانات").assertIsDisplayed()
        composeTestRule.onNodeWithText("إعادة المحاولة").performClick()
        assertTrue(retried)
    }

    @Test
    fun sectionsScreen_displaysPublishedSections() {
        val sections = listOf(
            StudySection(
                id = "sec1",
                subjectId = "math",
                name = "الفصل الأول: الأعداد المركبة",
                order = 1,
                isPublished = true
            )
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionsScreenContent(
                    uiState = SectionsUiState.Content(sections),
                    onRetry = {}
                )
            }
        }

        composeTestRule.onNodeWithText("الفصل الأول: الأعداد المركبة").assertIsDisplayed()
    }
}
