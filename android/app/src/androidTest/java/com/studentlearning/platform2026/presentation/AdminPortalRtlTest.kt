package com.studentlearning.platform2026.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.presentation.admin.AdminManagementSection
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

    @Test
    fun studyFileWithoutActiveVersion_disablesPublishButtonWithClearArabicReason() {
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
                        activeTab = AdminManagementSection.PDF_FILES,
                        studyFiles = listOf(
                            StudyFileMetadata(
                                id = "file-no-active",
                                sectionId = "sec-1",
                                title = "ملزمة بدون إصدار",
                                order = 1,
                                isPublished = false,
                                isArchived = false,
                                activeVersionId = null
                            )
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("نشر").assertIsNotEnabled()
        composeTestRule.onNodeWithText(
            "النشر معطّل: يتطلب نشر الملف وجود إصدار PDF فعّال موثّق (activeVersionId) من نافذة «إدارة الإصدارات»."
        ).assertIsDisplayed()
    }

    @Test
    fun archivedStudyFile_disablesPublishButtonWithClearArabicReason() {
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
                        activeTab = AdminManagementSection.PDF_FILES,
                        studyFiles = listOf(
                            StudyFileMetadata(
                                id = "file-archived",
                                sectionId = "sec-1",
                                title = "ملزمة مؤرشفة",
                                order = 1,
                                isPublished = false,
                                isArchived = true,
                                activeVersionId = "v1",
                                activeVersionNumber = 1,
                                activeVersionLabel = "1.0"
                            )
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("نشر").assertIsNotEnabled()
        composeTestRule.onNodeWithText(
            "النشر معطّل: الملف مؤرشف حالياً؛ قم باستعادته من الأرشيف أولاً قبل نشره للطلاب."
        ).assertIsDisplayed()
    }

    @Test
    fun readyDraftStudyFileWithValidActiveVersion_enablesPublishButtonWithArabicLabels() {
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
                        activeTab = AdminManagementSection.PDF_FILES,
                        studyFiles = listOf(
                            StudyFileMetadata(
                                id = "file-ready-draft",
                                sectionId = "sec-1",
                                title = "ملزمة الفيزياء الجاهزة",
                                order = 1,
                                isPublished = false,
                                isArchived = false,
                                activeVersionId = "v1",
                                activeVersionNumber = 1,
                                activeVersionLabel = "1.0"
                            )
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("ملزمة الفيزياء الجاهزة").assertIsDisplayed()
        composeTestRule.onNodeWithText("غير منشور").assertIsDisplayed()
        composeTestRule.onNodeWithText("الإصدار الفعّال 1.0 (رقم 1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("نشر").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText(
            "النشر معطّل: يتطلب نشر الملف وجود إصدار PDF فعّال موثّق (activeVersionId) من نافذة «إدارة الإصدارات»."
        ).assertDoesNotExist()
    }

    @Test
    fun draftStudyFileWithInvalidActiveVersionNumber_disablesPublishButtonWithArabicReason() {
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
                        activeTab = AdminManagementSection.PDF_FILES,
                        studyFiles = listOf(
                            StudyFileMetadata(
                                id = "file-invalid-ver-num",
                                sectionId = "sec-1",
                                title = "ملزمة برقم إصدار غير صالح",
                                order = 1,
                                isPublished = false,
                                isArchived = false,
                                activeVersionId = "v1",
                                activeVersionNumber = 0,
                                activeVersionLabel = "1.0"
                            )
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("نشر").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithText(
            "النشر معطّل: يتطلب نشر الملف وجود إصدار PDF فعّال موثّق (activeVersionId) من نافذة «إدارة الإصدارات»."
        ).assertIsDisplayed()
    }

    @Test
    fun publishedStudyFile_enablesUnpublishButtonEvenWhenVersionMetadataIncomplete() {
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
                        activeTab = AdminManagementSection.PDF_FILES,
                        studyFiles = listOf(
                            StudyFileMetadata(
                                id = "file-published-incomplete",
                                sectionId = "sec-1",
                                title = "ملزمة منشورة تحتاج معالجة",
                                order = 1,
                                isPublished = true,
                                isArchived = false,
                                activeVersionId = null,
                                activeVersionNumber = null
                            )
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("ملزمة منشورة تحتاج معالجة").assertIsDisplayed()
        composeTestRule.onNodeWithText("منشور").assertIsDisplayed()
        composeTestRule.onNodeWithText("بانتظار إضافة إصدار PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("إلغاء النشر").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun adminRole_disablesBranchMutationsAndSubjectUnpublishWithArabicNotices() {
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "admin-1",
                            email = "admin@example.com",
                            role = AdminRole.ADMIN,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        ),
                        activeTab = AdminManagementSection.BRANCHES,
                        branches = listOf(
                            StudyBranch("scientific", "السادس العلمي", 1, true)
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText(
            "تنبيه الصلاحيات: إنشاء الفروع وتعديلها ونشرها وترتيبها محصور بدور المالك (Owner) فقط حسب قواعد الأمان."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة فرع").assertIsNotEnabled()
        composeTestRule.onNodeWithText("تعديل").assertIsNotEnabled()
        composeTestRule.onNodeWithText("إلغاء النشر").assertIsNotEnabled()
    }

    @Test
    fun adminRole_disablesSubjectUnpublishButAllowsCreateSubject() {
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "admin-1",
                            email = "admin@example.com",
                            role = AdminRole.ADMIN,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        ),
                        activeTab = AdminManagementSection.SUBJECTS,
                        branches = listOf(StudyBranch("scientific", "السادس العلمي", 1, true)),
                        selectedBranchId = "scientific",
                        subjects = listOf(StudySubject("math", "scientific", "الرياضيات", 1, true))
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("إضافة مادة").assertIsEnabled()
        composeTestRule.onNodeWithText("تعديل").assertIsEnabled()
        composeTestRule.onNodeWithText("إلغاء النشر").assertIsNotEnabled()
    }

    @Test
    fun fileVersionsDialog_showsUploadPdfButtonWhenBrokerConfigured() {
        val file = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "ملزمة الكيمياء",
            order = 1,
            isPublished = false,
            isArchived = false
        )
        val draftUnverified = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-1",
            versionNumber = 1,
            versionLabel = "1.0",
            isPublished = false
        )
        composeTestRule.setContent {
            HaqibatiTheme {
                RenderAdminScreen(
                    uiState = AdminPortalUiState(
                        isCheckingSession = false,
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "admin-1",
                            email = "admin@example.com",
                            role = AdminRole.ADMIN,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        ),
                        r2BrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED,
                        versionsDialogFile = file,
                        fileVersions = listOf(draftUnverified)
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("رفع والتحقق من PDF").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("نشر وتفعيل هذا الإصدار").assertIsDisplayed().assertIsNotEnabled()
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
