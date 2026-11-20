package com.studentlearning.platform2026.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.presentation.admin.AdminPortalScreenContent
import com.studentlearning.platform2026.presentation.admin.AdminPortalUiState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminPortalRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun signedOutUser_seesSignInGateAndNoAdminControls() {
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(isSignedIn = false)
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("دخول الإدارة والمشرفين").assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة فرع").assertDoesNotExist()
    }

    @Test
    fun signedInUserWithUnloadedClaims_seesVerificationPendingGate() {
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "staff-1",
                            email = "staff@example.com",
                            role = AdminRole.NONE,
                            hasLoadedTokenClaims = false,
                            areClaimsConfirmedCurrent = false
                        ),
                        authErrorReason = AdminFailureReason.NETWORK_UNAVAILABLE
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("تعذّر تحديث بيانات الصلاحية حالياً").assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة فرع").assertDoesNotExist()
    }

    @Test
    fun signedInNonAdminUser_seesAccessDeniedGate() {
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "student-1",
                            email = "student@example.com",
                            role = AdminRole.NONE,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("غير مصرّح بالوصول إلى لوحة الإدارة").assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة فرع").assertDoesNotExist()
    }

    @Test
    fun ownerWithStaleClaims_remainsSignedInWithWarningAndDisabledMutations() {
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "owner-1",
                            email = "owner@example.com",
                            role = AdminRole.OWNER,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = false
                        ),
                        authErrorReason = AdminFailureReason.NETWORK_UNAVAILABLE,
                        branches = listOf(
                            StudyBranch("scientific", "السادس العلمي", 1, true)
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("المالك (Owner)").assertIsDisplayed()
        composeTestRule.onNodeWithText("صلاحيات العرض محفوظة مؤقتاً — التعديل موقوف").assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة فرع").assertIsNotEnabled()
    }

    @Test
    fun verifiedOwner_seesAdminDashboardAndBranches() {
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "owner-1",
                            email = "owner@example.com",
                            role = AdminRole.OWNER,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        ),
                        branches = listOf(
                            StudyBranch("scientific", "السادس العلمي", 1, true)
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("المالك (Owner)").assertIsDisplayed()
        composeTestRule.onNodeWithText("السادس العلمي").assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة فرع").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun RenderAdminScreen(uiState: AdminPortalUiState) {
        AdminPortalScreenContent(
            uiState = uiState,
            onNavigateBack = {},
            onRefreshSession = {},
            onSignIn = { _, _ -> },
            onSignOut = {},
            onSelectTab = {},
            onDismissFeedback = {},
            onRetryBranches = {},
            onRetrySubjects = {},
            onRetrySections = {},
            onRetryStudyFiles = {},
            onOpenCreateBranch = {},
            onOpenEditBranch = {},
            onOpenBranchSubjects = {},
            onToggleBranchPublish = {},
            onMoveBranch = { _, _ -> },
            onOpenCreateSubject = {},
            onOpenEditSubject = {},
            onOpenSubjectSections = {},
            onToggleSubjectPublish = {},
            onMoveSubject = { _, _ -> },
            onOpenCreateSection = {},
            onOpenEditSection = {},
            onOpenSectionFiles = {},
            onToggleSectionPublish = {},
            onMoveSection = { _, _ -> },
            onOpenCreateStudyFile = {},
            onOpenEditStudyFile = {},
            onToggleStudyFilePublish = {},
            onToggleStudyFileArchive = {},
            onMoveStudyFile = { _, _ -> },
            onDismissEditor = {},
            onSaveBranch = { _, _, _, _, _ -> },
            onSaveSubject = { _, _, _, _, _, _ -> },
            onSaveSection = { _, _, _, _, _, _ -> },
            onSaveStudyFile = { _, _, _, _, _, _, _ -> },
            onConfirmAction = {},
            onDismissConfirmation = {}
        )
    }
}
