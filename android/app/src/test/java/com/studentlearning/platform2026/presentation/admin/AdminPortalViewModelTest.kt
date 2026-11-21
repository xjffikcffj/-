package com.studentlearning.platform2026.presentation.admin

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.fakes.FakeAdminCatalogRepository
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminPortalViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `non-admin signed-in account cannot load admin catalog`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val catalogRepository = FakeAdminCatalogRepository().apply {
            branches.add(StudyBranch("scientific", "السادس العلمي", 1, true))
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.session.canPerformAdminActions)
        assertEquals(0, catalogRepository.fetchBranchesCalls)
        assertTrue(viewModel.uiState.value.branches.isEmpty())
    }

    @Test
    fun `owner sign-in unlocks dashboard and unpublish requires explicit confirmation`() = runTest {
        val authRepository = FakeAuthRepository()
        authRepository.signInHandler = { email, _ ->
            AppResult.Success(
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-1",
                    email = email,
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
        }
        val branch = StudyBranch("scientific", "السادس العلمي", 1, true)
        val catalogRepository = FakeAdminCatalogRepository().apply {
            branches.add(branch)
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.session.canPerformAdminActions)

        viewModel.signIn("owner@example.com", "secret-pass")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.session.canPerformAdminActions)
        assertEquals(listOf(branch), viewModel.uiState.value.branches)

        // Requesting unpublish must open confirmation dialog first without immediate mutation.
        viewModel.requestToggleBranchPublished(branch)
        assertTrue(viewModel.uiState.value.confirmationAction is AdminConfirmationAction.UnpublishBranch)
        assertNull(catalogRepository.lastPublishToggle)

        viewModel.confirmPendingAction()
        advanceUntilIdle()

        assertEquals("scientific" to false, catalogRepository.lastPublishToggle)
        assertFalse(viewModel.uiState.value.branches.first().isPublished)
    }

    @Test
    fun `admin role unlocks catalog management and supports creating branch`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "admin-1",
                email = "admin@example.com",
                role = AdminRole.ADMIN,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val catalogRepository = FakeAdminCatalogRepository()

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.session.canPerformAdminActions)
        viewModel.saveBranch(
            editing = null,
            customId = "vocational",
            name = "التعليم المهني",
            order = 3,
            isPublished = false
        )
        advanceUntilIdle()

        assertEquals(1, catalogRepository.writeAttemptCount)
        assertEquals(listOf("vocational"), viewModel.uiState.value.branches.map { it.id })
        assertEquals(AdminSuccessMessage.BRANCH_CREATED, viewModel.uiState.value.feedback?.successMessage)
    }

    @Test
    fun `archiving PDF metadata requires confirmation before mutating repository`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val pdfFile = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "ملزمة الفصل الأول",
            order = 1,
            isPublished = false,
            isArchived = false
        )
        val catalogRepository = FakeAdminCatalogRepository().apply {
            studyFiles.add(pdfFile)
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()

        viewModel.requestToggleStudyFileArchived(pdfFile)
        assertTrue(viewModel.uiState.value.confirmationAction is AdminConfirmationAction.ArchiveStudyFile)
        assertNull(catalogRepository.lastArchiveToggle)

        viewModel.confirmPendingAction()
        advanceUntilIdle()

        assertEquals("file-1" to true, catalogRepository.lastArchiveToggle)
    }

    @Test
    fun `network failure on session refresh keeps user signed in with unconfirmed claims and blocks writes`() = runTest {
        val ownerSession = AdminSession(
            isSignedIn = true,
            uid = "owner-1",
            email = "owner@example.com",
            role = AdminRole.OWNER,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        val authRepository = FakeAuthRepository(ownerSession)
        val branch = StudyBranch("scientific", "السادس العلمي", 1, true)
        val catalogRepository = FakeAdminCatalogRepository().apply {
            branches.add(branch)
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.session.canPerformAdminActions)

        authRepository.refreshHandler = {
            authRepository.setSession(ownerSession.copy(areClaimsConfirmedCurrent = false))
            AppResult.Error("Offline", AdminException(AdminFailureReason.NETWORK_UNAVAILABLE))
        }

        viewModel.refreshSession(forceTokenRefresh = true)
        advanceUntilIdle()

        val staleState = viewModel.uiState.value
        assertTrue(staleState.session.isSignedIn)
        assertEquals("owner-1", staleState.session.uid)
        assertTrue(staleState.session.hasRecognizedAdminRole)
        assertFalse(staleState.session.areClaimsConfirmedCurrent)
        assertFalse(staleState.session.canPerformAdminActions)
        assertEquals(AdminFailureReason.NETWORK_UNAVAILABLE, staleState.authErrorReason)

        // All write entry points must be blocked cleanly without calling repository.
        viewModel.saveBranch(null, null, "فرع جديد", 2, false)
        viewModel.requestToggleBranchPublished(branch)
        viewModel.moveBranch(branch, moveUp = false)
        advanceUntilIdle()

        assertEquals(0, catalogRepository.writeAttemptCount)
        assertNull(viewModel.uiState.value.confirmationAction)
        assertEquals(AdminFailureReason.SESSION_VERIFICATION_REQUIRED, viewModel.uiState.value.feedback?.failureReason)
    }

    @Test
    fun `signOut calls repository and resets session and catalog state`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val catalogRepository = FakeAdminCatalogRepository().apply {
            branches.add(StudyBranch("scientific", "السادس العلمي", 1, true))
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.branches.size)

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(1, authRepository.signOutCalls)
        assertFalse(viewModel.uiState.value.session.isSignedIn)
        assertTrue(viewModel.uiState.value.branches.isEmpty())
    }

    @Test
    fun `moveBranch failure surfaces error feedback and clears submitting state`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val branch1 = StudyBranch("scientific", "السادس العلمي", 1, true)
        val branch2 = StudyBranch("literary", "السادس الأدبي", 2, true)
        val catalogRepository = FakeAdminCatalogRepository().apply {
            branches.addAll(listOf(branch1, branch2))
            reorderFailure = AdminException(AdminFailureReason.PERMISSION_DENIED)
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()

        viewModel.moveBranch(branch2, moveUp = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSubmitting)
        assertEquals(true, viewModel.uiState.value.feedback?.isError)
        assertEquals(AdminFailureReason.PERMISSION_DENIED, viewModel.uiState.value.feedback?.failureReason)
    }

    @Test
    fun `version manager creates draft updates metadata and requires PDF proof before publish`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val file = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "ملزمة الأحياء",
            order = 1,
            isPublished = true,
            isArchived = false
        )
        val v1WithPdf = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-1",
            versionNumber = 1,
            versionLabel = "1.0",
            r2ObjectKey = "study-files/file-1/versions/v1/bio.pdf",
            fileSizeBytes = 10_240L,
            checksumSha256 = "e".repeat(64),
            isPublished = false
        )
        val catalogRepository = FakeAdminCatalogRepository().apply {
            studyFiles.add(file)
            versions.add(v1WithPdf)
            brokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
        }
        val viewModel = AdminPortalViewModel(
            authRepository = authRepository,
            adminCatalogRepository = catalogRepository,
            r2BrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
        )
        advanceUntilIdle()

        // Open version manager and verify suggested next version number = 2.
        viewModel.openFileVersionsManager(file)
        advanceUntilIdle()
        assertEquals("file-1", viewModel.uiState.value.versionsDialogFile?.id)
        assertEquals(listOf("v1"), viewModel.uiState.value.fileVersions.map { it.id })

        viewModel.openCreateVersionDialog()
        val editor = viewModel.uiState.value.editorDialog as AdminEditorDialogState.StudyFileVersionEditor
        assertEquals(2, editor.suggestedVersionNumber)

        // Create v2 draft.
        viewModel.saveStudyFileVersion(
            fileId = "file-1",
            editing = null,
            customVersionId = "v2",
            versionNumber = 2,
            versionLabel = "2.0",
            versionNotes = "إصلاح المسائل",
            publishAsActive = false
        )
        advanceUntilIdle()
        assertEquals(AdminSuccessMessage.VERSION_CREATED, viewModel.uiState.value.feedback?.successMessage)
        assertEquals(listOf("v2", "v1"), viewModel.uiState.value.fileVersions.map { it.id })

        // Update v2 metadata cleanly via editing.id.
        val draftV2 = viewModel.uiState.value.fileVersions.first { it.id == "v2" }
        viewModel.openEditVersionDialog(draftV2)
        viewModel.saveStudyFileVersion(
            fileId = "file-1",
            editing = draftV2,
            customVersionId = null,
            versionNumber = draftV2.versionNumber,
            versionLabel = "2.1",
            versionNotes = "تحديث نهائي",
            publishAsActive = false
        )
        advanceUntilIdle()
        assertEquals(AdminSuccessMessage.VERSION_UPDATED, viewModel.uiState.value.feedback?.successMessage)
        assertEquals("2.1", viewModel.uiState.value.fileVersions.first { it.id == "v2" }.versionLabel)

        // Publishing v2 without an uploaded PDF binary must fail cleanly.
        val updatedV2 = viewModel.uiState.value.fileVersions.first { it.id == "v2" }
        viewModel.requestPublishStudyFileVersion(updatedV2)
        assertTrue(viewModel.uiState.value.confirmationAction is AdminConfirmationAction.PublishStudyFileVersion)
        viewModel.confirmPendingAction()
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.feedback?.isError)
        assertEquals(
            AdminValidationError.VERSION_PDF_BINARY_MISSING,
            viewModel.uiState.value.feedback?.validationError
        )

        // Publishing v1 with verified PDF binary succeeds and sets active pointers while preserving v2.
        viewModel.requestPublishStudyFileVersion(v1WithPdf)
        viewModel.confirmPendingAction()
        advanceUntilIdle()
        assertEquals(AdminSuccessMessage.VERSION_PUBLISHED, viewModel.uiState.value.feedback?.successMessage)
        assertEquals("v1", catalogRepository.studyFiles.first().activeVersionId)
        assertEquals(2, catalogRepository.versions.count { it.fileId == "file-1" })
    }

    @Test
    fun `archived parent study file cannot be republished until unarchived and requires active published version`() =
        runTest {
            val authRepository = FakeAuthRepository(
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-1",
                    email = "owner@example.com",
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val archivedFile = StudyFileMetadata(
                id = "file-archived",
                sectionId = "sec-1",
                title = "ملف مؤرشف",
                order = 1,
                isPublished = false,
                isArchived = true,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val activeVersion = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-archived",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-archived/versions/v1/document.pdf",
                fileSizeBytes = 8192L,
                checksumSha256 = "f".repeat(64),
                isPublished = true,
                isArchived = false
            )
            val draftFileNoVersion = StudyFileMetadata(
                id = "file-no-version",
                sectionId = "sec-1",
                title = "ملف بلا إصدار",
                order = 2,
                isPublished = false,
                isArchived = false,
                activeVersionId = null
            )
            val catalogRepository = FakeAdminCatalogRepository().apply {
                studyFiles.addAll(listOf(archivedFile, draftFileNoVersion))
                versions.add(activeVersion)
            }
            val viewModel = AdminPortalViewModel(
                authRepository = authRepository,
                adminCatalogRepository = catalogRepository
            )
            advanceUntilIdle()

            // Parent file without an active published version cannot be published.
            viewModel.requestToggleStudyFilePublished(draftFileNoVersion)
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.feedback?.isError)
            assertEquals(
                AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                viewModel.uiState.value.feedback?.validationError
            )
            assertFalse(catalogRepository.studyFiles[1].isPublished)

            // Archived file cannot be published until unarchived.
            viewModel.requestToggleStudyFilePublished(archivedFile)
            advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.feedback?.isError)
            assertEquals(
                AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                viewModel.uiState.value.feedback?.validationError
            )
            assertFalse(catalogRepository.studyFiles.first().isPublished)

            viewModel.requestToggleStudyFileArchived(archivedFile)
            advanceUntilIdle()
            assertFalse(catalogRepository.studyFiles.first().isArchived)

            viewModel.requestToggleStudyFilePublished(catalogRepository.studyFiles.first())
            advanceUntilIdle()
            assertTrue(catalogRepository.studyFiles.first().isPublished)

            // Creating a new study file as a draft succeeds while broker is pending.
            viewModel.saveStudyFile(
                sectionId = "sec-1",
                editing = null,
                customId = "draft-file-2",
                title = "مسودة جديدة",
                description = null,
                order = 3,
                isPublished = false
            )
            advanceUntilIdle()
            assertEquals(AdminSuccessMessage.FILE_CREATED, viewModel.uiState.value.feedback?.successMessage)
            assertFalse(catalogRepository.studyFiles.first { it.id == "draft-file-2" }.isPublished)
        }

    @Test
    fun `archiving published file unpublishes atomically and restoring keeps it unpublished while preserving versions`() =
        runTest {
            val authRepository = FakeAuthRepository(
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-1",
                    email = "owner@example.com",
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val publishedFile = StudyFileMetadata(
                id = "file-live",
                sectionId = "sec-1",
                title = "ملزمة الفيزياء",
                order = 1,
                isPublished = true,
                isArchived = false,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val nullNumberFile = publishedFile.copy(
                id = "file-null-num",
                isPublished = false,
                activeVersionNumber = null
            )
            val mismatchNumberFile = publishedFile.copy(
                id = "file-mismatch-num",
                isPublished = false,
                activeVersionNumber = 99
            )
            val catalogRepository = FakeAdminCatalogRepository().apply {
                studyFiles.addAll(listOf(publishedFile, nullNumberFile, mismatchNumberFile))
                versions.addAll(
                    listOf(
                        StudyFileVersionMetadata(
                            id = "v1",
                            fileId = "file-live",
                            versionNumber = 1,
                            versionLabel = "1.0",
                            r2ObjectKey = "study-files/file-live/versions/v1/document.pdf",
                            fileSizeBytes = 4096L,
                            checksumSha256 = "a".repeat(64),
                            isPublished = true
                        ),
                        StudyFileVersionMetadata(
                            id = "v1",
                            fileId = "file-null-num",
                            versionNumber = 1,
                            versionLabel = "1.0",
                            r2ObjectKey = "study-files/file-null-num/versions/v1/document.pdf",
                            fileSizeBytes = 4096L,
                            checksumSha256 = "b".repeat(64),
                            isPublished = true
                        ),
                        StudyFileVersionMetadata(
                            id = "v1",
                            fileId = "file-mismatch-num",
                            versionNumber = 1,
                            versionLabel = "1.0",
                            r2ObjectKey = "study-files/file-mismatch-num/versions/v1/document.pdf",
                            fileSizeBytes = 4096L,
                            checksumSha256 = "c".repeat(64),
                            isPublished = true
                        )
                    )
                )
            }
            val viewModel = AdminPortalViewModel(
                authRepository = authRepository,
                adminCatalogRepository = catalogRepository
            )
            advanceUntilIdle()

            // Archiving a published file requires confirmation and atomically sets isPublished = false
            // while keeping activeVersionId and version records intact.
            viewModel.requestToggleStudyFileArchived(publishedFile)
            assertTrue(viewModel.uiState.value.confirmationAction is AdminConfirmationAction.ArchiveStudyFile)
            viewModel.confirmPendingAction()
            advanceUntilIdle()

            val archivedState = catalogRepository.studyFiles.first { it.id == "file-live" }
            assertTrue(archivedState.isArchived)
            assertFalse(archivedState.isPublished)
            assertEquals("v1", archivedState.activeVersionId)
            assertEquals(1, catalogRepository.versions.count { it.fileId == "file-live" })

            // Restoring from archive must keep isPublished = false (never auto-publish).
            viewModel.requestToggleStudyFileArchived(archivedState)
            advanceUntilIdle()

            val restoredState = catalogRepository.studyFiles.first { it.id == "file-live" }
            assertFalse(restoredState.isArchived)
            assertFalse(restoredState.isPublished)
            assertEquals("v1", restoredState.activeVersionId)

            // Reject publishing when activeVersionNumber is null.
            viewModel.requestToggleStudyFilePublished(nullNumberFile)
            advanceUntilIdle()
            assertEquals(
                AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                viewModel.uiState.value.feedback?.validationError
            )
            assertFalse(catalogRepository.studyFiles.first { it.id == "file-null-num" }.isPublished)

            // Reject publishing when activeVersionNumber mismatches the active version's versionNumber.
            viewModel.requestToggleStudyFilePublished(mismatchNumberFile)
            advanceUntilIdle()
            assertEquals(
                AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                viewModel.uiState.value.feedback?.validationError
            )
            assertFalse(catalogRepository.studyFiles.first { it.id == "file-mismatch-num" }.isPublished)
        }
}
