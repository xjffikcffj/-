package com.studentlearning.platform2026.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.presentation.admin.AdminEditorDialogHost
import com.studentlearning.platform2026.presentation.admin.AdminEditorDialogState
import com.studentlearning.platform2026.presentation.admin.AdminManagementSection
import com.studentlearning.platform2026.presentation.admin.AdminPortalScreenContent
import com.studentlearning.platform2026.presentation.admin.AdminPortalUiState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Assert.assertEquals
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
                                archiveStatus = ArchiveStatus.UNARCHIVED,
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
                                archiveStatus = ArchiveStatus.ARCHIVED,
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
                                archiveStatus = ArchiveStatus.UNARCHIVED,
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
                                archiveStatus = ArchiveStatus.UNARCHIVED,
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
                                archiveStatus = ArchiveStatus.UNARCHIVED,
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
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        val draftUnverified = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-1",
            versionNumber = 1,
            versionLabel = "1.0",
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED
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

    @Test
    fun createVersionDialog_hidesImmediatePublishSwitchAndShowsDraftWorkflowNotice() {
        val file = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "ملزمة الكيمياء",
            order = 1,
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        var capturedPublishAsActive: Boolean? = null
        var saveCalls = 0
        composeTestRule.setContent {
            HaqibatiTheme {
                AdminEditorDialogHost(
                    dialogState = AdminEditorDialogState.StudyFileVersionEditor(
                        file = file,
                        editing = null,
                        suggestedVersionNumber = 2
                    ),
                    isSubmitting = false,
                    r2BrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED,
                    onDismiss = {},
                    onSaveBranch = { _, _, _, _ -> },
                    onSaveSubject = { _, _, _, _, _ -> },
                    onSaveSection = { _, _, _, _, _ -> },
                    onSaveStudyFile = { _, _, _, _, _, _ -> },
                    onSaveStudyFileVersion = { _, _, _, _, _, publishAsActive ->
                        saveCalls++
                        capturedPublishAsActive = publishAsActive
                    }
                )
            }
        }

        composeTestRule.onNodeWithText(
            "تفعيل ونشر هذا الإصدار فوراً كأحدث إصدار للطلاب"
        ).assertDoesNotExist()
        composeTestRule.onNodeWithText(
            "يُنشأ الإصدار الجديد كمسودة غير منشورة أولاً؛ بعد الحفظ ارفع ملف PDF وتحقق منه من نافذة «إدارة الإصدارات» ثم انشره يدوياً."
        ).assertIsDisplayed()

        composeTestRule.onNodeWithText("حفظ").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, saveCalls)
        assertEquals(false, capturedPublishAsActive)
    }

    @Test
    fun unspecifiedArchiveStudyFile_showsArabicWarningAndDisablesPublishAndArchiveWhileKeepingInspectActions() {
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
                                id = "file-unspec",
                                sectionId = "sec-1",
                                title = "ملزمة غير محددة الأرشفة",
                                order = 1,
                                isPublished = false,
                                archiveStatus = ArchiveStatus.UNSPECIFIED,
                                activeVersionId = "v1",
                                activeVersionNumber = 1,
                                activeVersionLabel = "1.0"
                            )
                        )
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("حالة الأرشفة غير محددة").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "حالة الأرشفة غير محددة — هذه الوثيقة تحتاج إلى مراجعة قبل النشر"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("نشر").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithText("أرشفة").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithText("تعديل").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("إدارة الإصدارات").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun unspecifiedArchiveStudyFile_disabledPublishAndArchiveDoNotInvokeCallbacksOnClickWhileEditAndUnpublishDo() {
        var publishCalls = 0
        var archiveCalls = 0
        var editCalls = 0
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
                                id = "file-unspec",
                                sectionId = "sec-1",
                                title = "ملزمة غير محددة الأرشفة",
                                order = 1,
                                isPublished = false,
                                archiveStatus = ArchiveStatus.UNSPECIFIED,
                                activeVersionId = "v1",
                                activeVersionNumber = 1,
                                activeVersionLabel = "1.0"
                            )
                        )
                    ),
                    onOpenEditStudyFile = { editCalls++ },
                    onToggleStudyFilePublish = { publishCalls++ },
                    onToggleStudyFileArchive = { archiveCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithText("نشر").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("أرشفة").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("تعديل").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, publishCalls)
        assertEquals(0, archiveCalls)
        assertEquals(1, editCalls)
    }

    @Test
    fun publishedUnspecifiedArchiveStudyFile_allowsUnpublishCallbackOnly() {
        var unpublishCalls = 0
        var archiveCalls = 0
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
                                id = "file-unspec-live",
                                sectionId = "sec-1",
                                title = "ملزمة منشورة غير محددة الأرشفة",
                                order = 1,
                                isPublished = true,
                                archiveStatus = ArchiveStatus.UNSPECIFIED,
                                activeVersionId = "v1",
                                activeVersionNumber = 1,
                                activeVersionLabel = "1.0"
                            )
                        )
                    ),
                    onToggleStudyFilePublish = { unpublishCalls++ },
                    onToggleStudyFileArchive = { archiveCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithText(
            "حالة الأرشفة غير محددة — هذه الوثيقة تحتاج إلى مراجعة قبل النشر"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("إلغاء النشر").assertIsDisplayed().assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("أرشفة").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, unpublishCalls)
        assertEquals(0, archiveCalls)
    }

    @Test
    fun fileVersionsDialog_case6_parentUnspecifiedWithUnarchivedVerifiedVersion_blocksCreateUploadPublishAndAllowsEdit() {
        val unspecifiedParentFile = StudyFileMetadata(
            id = "file-unspec",
            sectionId = "sec-1",
            title = "ملزمة غير محددة",
            order = 1,
            isPublished = false,
            archiveStatus = ArchiveStatus.UNSPECIFIED
        )
        val unarchivedVerifiedVersion = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-unspec",
            versionNumber = 1,
            versionLabel = "1.0",
            versionNotes = "ملاحظات معاينة",
            r2ObjectKey = "study-files/file-unspec/versions/v1/doc.pdf",
            fileSizeBytes = 2048L,
            checksumSha256 = "a".repeat(64),
            verifiedAttemptId = "attempt-123",
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        var createCalls = 0
        var uploadCalls = 0
        var publishCalls = 0
        var editCalls = 0
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
                        versionsDialogFile = unspecifiedParentFile,
                        fileVersions = listOf(unarchivedVerifiedVersion)
                    ),
                    onOpenCreateVersion = { createCalls++ },
                    onOpenEditVersion = { editCalls++ },
                    onRequestUploadVersionPdf = { uploadCalls++ },
                    onRequestPublishVersion = { publishCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithText("أرشفة الملف الأب غير محددة").assertIsDisplayed()
        composeTestRule.onNodeWithText("مسودة غير منشورة").assertDoesNotExist()
        composeTestRule.onNodeWithText(
            "حالة أرشفة الملف الأب غير محددة — هذا الإصدار يحتاج إلى مراجعة ويُمنع رفع PDF جديد أو نشره حتى تُحسم حالة الأرشفة."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة إصدار جديد").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("رفع والتحقق من PDF").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("نشر وتفعيل هذا الإصدار").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("تعديل").assertIsDisplayed().assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("ملاحظات التحديث: ملاحظات معاينة").assertIsDisplayed()
        composeTestRule.waitForIdle()
        assertEquals(0, createCalls)
        assertEquals(0, uploadCalls)
        assertEquals(0, publishCalls)
        assertEquals(1, editCalls)
    }

    @Test
    fun fileVersionsDialog_case7_unarchivedParentWithUnspecifiedVersion_allowsAddVersionOnlyAndBlocksVersionUploadPublish() {
        val unarchivedParentFile = StudyFileMetadata(
            id = "file-ok",
            sectionId = "sec-1",
            title = "ملزمة سليمة",
            order = 1,
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        val unspecifiedVersion = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-ok",
            versionNumber = 1,
            versionLabel = "1.0",
            versionNotes = "ملاحظات معاينة",
            r2ObjectKey = "study-files/file-ok/versions/v1/doc.pdf",
            fileSizeBytes = 2048L,
            checksumSha256 = "a".repeat(64),
            verifiedAttemptId = "attempt-123",
            isPublished = false,
            archiveStatus = ArchiveStatus.UNSPECIFIED
        )
        var createCalls = 0
        var uploadCalls = 0
        var publishCalls = 0
        var editCalls = 0
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
                        versionsDialogFile = unarchivedParentFile,
                        fileVersions = listOf(unspecifiedVersion)
                    ),
                    onOpenCreateVersion = { createCalls++ },
                    onOpenEditVersion = { editCalls++ },
                    onRequestUploadVersionPdf = { uploadCalls++ },
                    onRequestPublishVersion = { publishCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithText("حالة الأرشفة غير محددة").assertIsDisplayed()
        composeTestRule.onNodeWithText("مسودة غير منشورة").assertDoesNotExist()
        composeTestRule.onNodeWithText(
            "حالة أرشفة هذا الإصدار غير محددة — يُمنع رفع PDF جديد أو نشره حتى تُراجع الوثيقة."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("إضافة إصدار جديد").assertIsDisplayed().assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("رفع والتحقق من PDF").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("نشر وتفعيل هذا الإصدار").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("تعديل").assertIsDisplayed().assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, createCalls)
        assertEquals(0, uploadCalls)
        assertEquals(0, publishCalls)
        assertEquals(1, editCalls)
    }

    @androidx.compose.runtime.Composable
    private fun RenderAdminScreen(
        uiState: AdminPortalUiState,
        onOpenEditStudyFile: (StudyFileMetadata) -> Unit = {},
        onToggleStudyFilePublish: (StudyFileMetadata) -> Unit = {},
        onToggleStudyFileArchive: (StudyFileMetadata) -> Unit = {},
        onOpenCreateVersion: () -> Unit = {},
        onOpenEditVersion: (StudyFileVersionMetadata) -> Unit = {},
        onRequestUploadVersionPdf: (StudyFileVersionMetadata) -> Unit = {},
        onRequestPublishVersion: (StudyFileVersionMetadata) -> Unit = {}
    ) {
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
            onOpenEditStudyFile = onOpenEditStudyFile,
            onToggleStudyFilePublish = onToggleStudyFilePublish,
            onToggleStudyFileArchive = onToggleStudyFileArchive,
            onMoveStudyFile = { _, _ -> },
            onOpenCreateVersion = onOpenCreateVersion,
            onOpenEditVersion = onOpenEditVersion,
            onRequestUploadVersionPdf = onRequestUploadVersionPdf,
            onRequestPublishVersion = onRequestPublishVersion,
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
