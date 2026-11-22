package com.studentlearning.platform2026.presentation.admin

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.AuditLogEntry
import com.studentlearning.platform2026.domain.model.StaffMember
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.fakes.FakeAdminCatalogRepository
import com.studentlearning.platform2026.fakes.FakeAdminGovernanceRepository
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
    fun `admin role blocks owner-only branch and subject unpublish ops while allowing subject creation`() = runTest {
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
        val existingBranch = StudyBranch("scientific", "السادس العلمي", 1, true)
        val catalogRepository = FakeAdminCatalogRepository().apply {
            branches.add(existingBranch)
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.session.canPerformAdminActions)
        assertFalse(viewModel.uiState.value.session.canPerformOwnerOnlyActions)

        viewModel.saveBranch(
            editing = null,
            customId = "vocational",
            name = "التعليم المهني",
            order = 3,
            isPublished = false
        )
        viewModel.requestToggleBranchPublished(existingBranch)
        viewModel.moveBranch(existingBranch, moveUp = false)
        viewModel.requestToggleSubjectPublished(
            com.studentlearning.platform2026.domain.model.StudySubject(
                id = "math",
                branchId = "scientific",
                name = "الرياضيات",
                order = 1,
                isPublished = true
            )
        )
        advanceUntilIdle()

        assertEquals(0, catalogRepository.writeAttemptCount)
        assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, viewModel.uiState.value.feedback?.failureReason)
        assertNull(viewModel.uiState.value.confirmationAction)

        viewModel.saveSubject(
            branchId = "scientific",
            editing = null,
            customId = "physics",
            name = "الفيزياء",
            order = 1,
            isPublished = false
        )
        advanceUntilIdle()

        assertEquals(1, catalogRepository.writeAttemptCount)
        assertEquals(listOf("physics"), viewModel.uiState.value.subjects.map { it.id })
        assertEquals(AdminSuccessMessage.SUBJECT_CREATED, viewModel.uiState.value.feedback?.successMessage)
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
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        val catalogRepository = FakeAdminCatalogRepository().apply {
            studyFiles.add(pdfFile)
        }

        val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
        advanceUntilIdle()
        viewModel.openSectionFiles(StudySection("sec-1", "math", "القسم", 1, true))
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
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        val v1WithPdf = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-1",
            versionNumber = 1,
            versionLabel = "1.0",
            r2ObjectKey = "study-files/file-1/versions/v1/bio.pdf",
            fileSizeBytes = 10_240L,
            checksumSha256 = "e".repeat(64),
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED
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
                archiveStatus = ArchiveStatus.ARCHIVED,
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
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val draftFileNoVersion = StudyFileMetadata(
                id = "file-no-version",
                sectionId = "sec-1",
                title = "ملف بلا إصدار",
                order = 2,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED,
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
            viewModel.openSectionFiles(StudySection("sec-1", "math", "القسم", 1, true))
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
                archiveStatus = ArchiveStatus.UNARCHIVED,
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
                            isPublished = true,
                            archiveStatus = ArchiveStatus.UNARCHIVED
                        ),
                        StudyFileVersionMetadata(
                            id = "v1",
                            fileId = "file-null-num",
                            versionNumber = 1,
                            versionLabel = "1.0",
                            r2ObjectKey = "study-files/file-null-num/versions/v1/document.pdf",
                            fileSizeBytes = 4096L,
                            checksumSha256 = "b".repeat(64),
                            isPublished = true,
                            archiveStatus = ArchiveStatus.UNARCHIVED
                        ),
                        StudyFileVersionMetadata(
                            id = "v1",
                            fileId = "file-mismatch-num",
                            versionNumber = 1,
                            versionLabel = "1.0",
                            r2ObjectKey = "study-files/file-mismatch-num/versions/v1/document.pdf",
                            fileSizeBytes = 4096L,
                            checksumSha256 = "c".repeat(64),
                            isPublished = true,
                            archiveStatus = ArchiveStatus.UNARCHIVED
                        )
                    )
                )
            }
            val viewModel = AdminPortalViewModel(
                authRepository = authRepository,
                adminCatalogRepository = catalogRepository
            )
            advanceUntilIdle()
            viewModel.openSectionFiles(StudySection("sec-1", "math", "القسم", 1, true))
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

    @Test
    fun `uploadAndVerifyVersionPdf verifies version binary and surfaces VERSION_PDF_VERIFIED success`() = runTest {
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
        val draftFile = StudyFileMetadata(
            id = "file-up",
            sectionId = "sec-1",
            title = "ملزمة الرفع",
            order = 1,
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        val draftVersion = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-up",
            versionNumber = 1,
            versionLabel = "1.0",
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        val catalogRepository = FakeAdminCatalogRepository().apply {
            brokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
            studyFiles.add(draftFile)
            versions.add(draftVersion)
        }
        val viewModel = AdminPortalViewModel(
            authRepository = authRepository,
            adminCatalogRepository = catalogRepository,
            r2BrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
        )
        advanceUntilIdle()

        viewModel.openFileVersionsManager(draftFile)
        advanceUntilIdle()

        val validSha = "a".repeat(64)
        val binary = com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource(
            sizeBytes = 2048L,
            sha256Hex = validSha,
            displayName = "ch1.pdf",
            openInputStream = { "%PDF-1.7\n".toByteArray().inputStream() }
        )
        viewModel.uploadAndVerifyVersionPdf("file-up", "v1", binary)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.uploadingVersionId)
        assertEquals(AdminSuccessMessage.VERSION_PDF_VERIFIED, viewModel.uiState.value.feedback?.successMessage)
        assertTrue(viewModel.uiState.value.fileVersions.first().hasUploadedPdfBinary)

        viewModel.reportVersionUploadError(AdminValidationError.VERSION_UPLOAD_INVALID_PDF)
        assertEquals(true, viewModel.uiState.value.feedback?.isError)
        assertEquals(AdminValidationError.VERSION_UPLOAD_INVALID_PDF, viewModel.uiState.value.feedback?.validationError)
    }

    @Test
    fun `unspecified archive status blocks publish restore upload and version publish while preserving unpublish when live`() =
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
            val unspecifiedUnpublished = StudyFileMetadata(
                id = "file-unspec-draft",
                sectionId = "sec-1",
                title = "وثيقة قديمة غير منشورة",
                order = 1,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNSPECIFIED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val unspecifiedPublished = StudyFileMetadata(
                id = "file-unspec-live",
                sectionId = "sec-1",
                title = "وثيقة قديمة منشورة",
                order = 2,
                isPublished = true,
                archiveStatus = ArchiveStatus.UNSPECIFIED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val unarchivedVersion = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-unspec-draft",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-unspec-draft/versions/v1/document.pdf",
                fileSizeBytes = 2048L,
                checksumSha256 = "a".repeat(64),
                isPublished = true,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val unspecifiedVersion = StudyFileVersionMetadata(
                id = "v2",
                fileId = "file-unspec-draft",
                versionNumber = 2,
                versionLabel = "2.0",
                r2ObjectKey = "study-files/file-unspec-draft/versions/v2/document.pdf",
                fileSizeBytes = 2048L,
                checksumSha256 = "b".repeat(64),
                isPublished = false,
                archiveStatus = ArchiveStatus.UNSPECIFIED
            )
            val catalogRepository = FakeAdminCatalogRepository().apply {
                brokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
                studyFiles.addAll(listOf(unspecifiedUnpublished, unspecifiedPublished))
                versions.addAll(listOf(unarchivedVersion, unspecifiedVersion))
            }
            val viewModel = AdminPortalViewModel(
                authRepository = authRepository,
                adminCatalogRepository = catalogRepository,
                r2BrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
            )
            advanceUntilIdle()
            viewModel.openSectionFiles(StudySection("sec-1", "math", "القسم", 1, true))
            advanceUntilIdle()

            val initialWrites = catalogRepository.writeAttemptCount

            // Unpublished UNSPECIFIED file cannot be published or archive-toggled.
            viewModel.requestToggleStudyFilePublished(unspecifiedUnpublished)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertEquals(initialWrites, catalogRepository.writeAttemptCount)

            viewModel.requestToggleStudyFileArchived(unspecifiedUnpublished)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertNull(viewModel.uiState.value.confirmationAction)
            assertEquals(initialWrites, catalogRepository.writeAttemptCount)

            // Versions manager blocks adding, uploading, or publishing under UNSPECIFIED state.
            viewModel.openFileVersionsManager(unspecifiedUnpublished)
            advanceUntilIdle()
            viewModel.openCreateVersionDialog()
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertNull(viewModel.uiState.value.editorDialog)

            viewModel.requestPublishStudyFileVersion(unspecifiedVersion)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertNull(viewModel.uiState.value.confirmationAction)

            val binary = com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource(
                sizeBytes = 2048L,
                sha256Hex = "c".repeat(64),
                displayName = "ch1.pdf",
                openInputStream = { "%PDF-1.7\n".toByteArray().inputStream() }
            )
            viewModel.uploadAndVerifyVersionPdf("file-unspec-draft", "v2", binary)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertEquals(initialWrites, catalogRepository.writeAttemptCount)

            // Already-published UNSPECIFIED file can still be unpublished cleanly.
            viewModel.requestToggleStudyFilePublished(unspecifiedPublished)
            assertTrue(viewModel.uiState.value.confirmationAction is AdminConfirmationAction.UnpublishStudyFile)
            viewModel.confirmPendingAction()
            advanceUntilIdle()
            assertFalse(catalogRepository.studyFiles.first { it.id == "file-unspec-live" }.isPublished)
        }

    @Test
    fun `direct ViewModel bypass calls reject UNSPECIFIED ARCHIVED and missing context while allowing metadata edits`() =
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
            val section = com.studentlearning.platform2026.domain.model.StudySection(
                id = "sec-1",
                subjectId = "math",
                name = "الفصل الأول",
                order = 1,
                isPublished = true
            )
            val unspecFile = StudyFileMetadata(
                id = "file-unspec",
                sectionId = "sec-1",
                title = "وثيقة غير محددة",
                order = 1,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNSPECIFIED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val archivedFile = StudyFileMetadata(
                id = "file-archived",
                sectionId = "sec-1",
                title = "وثيقة مؤرشفة",
                order = 2,
                isPublished = false,
                archiveStatus = ArchiveStatus.ARCHIVED
            )
            val unarchivedFile = StudyFileMetadata(
                id = "file-ok",
                sectionId = "sec-1",
                title = "وثيقة سليمة",
                order = 3,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val unspecVersionOnOkFile = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-ok",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-ok/versions/v1/doc.pdf",
                fileSizeBytes = 2048L,
                checksumSha256 = "a".repeat(64),
                isPublished = false,
                archiveStatus = ArchiveStatus.UNSPECIFIED
            )
            val archivedVersionOnOkFile = StudyFileVersionMetadata(
                id = "v2",
                fileId = "file-ok",
                versionNumber = 2,
                versionLabel = "2.0",
                r2ObjectKey = "study-files/file-ok/versions/v2/doc.pdf",
                fileSizeBytes = 2048L,
                checksumSha256 = "b".repeat(64),
                isPublished = false,
                archiveStatus = ArchiveStatus.ARCHIVED
            )
            val unarchivedVersionOnUnspecFile = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-unspec",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-unspec/versions/v1/doc.pdf",
                fileSizeBytes = 2048L,
                checksumSha256 = "c".repeat(64),
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val catalogRepository = FakeAdminCatalogRepository().apply {
                brokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
                studyFiles.addAll(listOf(unspecFile, archivedFile, unarchivedFile))
                versions.addAll(
                    listOf(
                        unspecVersionOnOkFile,
                        archivedVersionOnOkFile,
                        unarchivedVersionOnUnspecFile
                    )
                )
            }
            val viewModel = AdminPortalViewModel(
                authRepository = authRepository,
                adminCatalogRepository = catalogRepository,
                r2BrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
            )
            advanceUntilIdle()

            val binary = com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource(
                sizeBytes = 2048L,
                sha256Hex = "d".repeat(64),
                displayName = "ch1.pdf",
                openInputStream = { "%PDF-1.7\n".toByteArray().inputStream() }
            )

            // 1. Calling directly BEFORE parent/version context is loaded fails cleanly with 0 repo writes.
            viewModel.saveStudyFileVersion(
                fileId = "file-ok",
                editing = null,
                customVersionId = "v99",
                versionNumber = 99,
                versionLabel = "99.0",
                versionNotes = null,
                publishAsActive = false
            )
            assertEquals(AdminValidationError.EMPTY_PARENT_REFERENCE, viewModel.uiState.value.feedback?.validationError)
            assertEquals(0, catalogRepository.writeAttemptCount)

            viewModel.requestPublishStudyFileVersion(unspecVersionOnOkFile)
            assertEquals(AdminValidationError.EMPTY_PARENT_REFERENCE, viewModel.uiState.value.feedback?.validationError)
            assertNull(viewModel.uiState.value.confirmationAction)

            viewModel.uploadAndVerifyVersionPdf("file-ok", "v1", binary)
            assertEquals(AdminValidationError.EMPTY_PARENT_REFERENCE, viewModel.uiState.value.feedback?.validationError)
            assertEquals(0, catalogRepository.writeAttemptCount)

            // 2. Load section files and test mismatched stale argument vs authoritative UNSPECIFIED state.
            viewModel.openSectionFiles(section)
            advanceUntilIdle()
            val forgedUnarchivedCopy = unspecFile.copy(archiveStatus = ArchiveStatus.UNARCHIVED)
            viewModel.requestToggleStudyFilePublished(forgedUnarchivedCopy)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertEquals(0, catalogRepository.writeAttemptCount)

            viewModel.requestToggleStudyFileArchived(forgedUnarchivedCopy)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertNull(viewModel.uiState.value.confirmationAction)
            assertEquals(0, catalogRepository.writeAttemptCount)

            // 3. Direct saveStudyFileVersion / uploadAndVerifyVersionPdf / requestPublishStudyFileVersion under UNSPECIFIED/ARCHIVED parent.
            viewModel.openFileVersionsManager(unspecFile)
            advanceUntilIdle()
            viewModel.saveStudyFileVersion(
                fileId = "file-unspec",
                editing = null,
                customVersionId = "v10",
                versionNumber = 10,
                versionLabel = "10.0",
                versionNotes = null,
                publishAsActive = false
            )
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertEquals(0, catalogRepository.writeAttemptCount)

            viewModel.uploadAndVerifyVersionPdf("file-unspec", "v1", binary)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertEquals(0, catalogRepository.writeAttemptCount)

            viewModel.requestPublishStudyFileVersion(unarchivedVersionOnUnspecFile)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertNull(viewModel.uiState.value.confirmationAction)

            // Non-sensitive metadata edits on UNSPECIFIED parent & UNSPECIFIED version remain allowed.
            viewModel.saveStudyFile(
                sectionId = "sec-1",
                editing = unspecFile,
                customId = null,
                title = "وثيقة غير محددة محدثة",
                description = "وصف مراجعة",
                order = 1,
                isPublished = false
            )
            advanceUntilIdle()
            assertEquals(AdminSuccessMessage.FILE_UPDATED, viewModel.uiState.value.feedback?.successMessage)
            assertEquals(1, catalogRepository.writeAttemptCount)

            // 4. Parent ARCHIVED blocks saveStudyFileVersion & uploadAndVerifyVersionPdf with 0 extra writes.
            viewModel.openFileVersionsManager(archivedFile)
            advanceUntilIdle()
            val writesBeforeArchivedParent = catalogRepository.writeAttemptCount
            viewModel.saveStudyFileVersion(
                fileId = "file-archived",
                editing = null,
                customVersionId = "v3",
                versionNumber = 3,
                versionLabel = "3.0",
                versionNotes = null,
                publishAsActive = false
            )
            assertEquals(
                AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                viewModel.uiState.value.feedback?.validationError
            )
            assertEquals(writesBeforeArchivedParent, catalogRepository.writeAttemptCount)

            // 5. Parent UNARCHIVED with UNSPECIFIED / ARCHIVED versions blocks upload & publish with 0 extra writes.
            viewModel.openFileVersionsManager(unarchivedFile)
            advanceUntilIdle()
            val writesBeforeVersionChecks = catalogRepository.writeAttemptCount

            viewModel.uploadAndVerifyVersionPdf("file-ok", "v1", binary)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertEquals(writesBeforeVersionChecks, catalogRepository.writeAttemptCount)

            viewModel.uploadAndVerifyVersionPdf("file-ok", "v2", binary)
            assertEquals(
                AdminValidationError.VERSION_ARCHIVED_CANNOT_PUBLISH,
                viewModel.uiState.value.feedback?.validationError
            )
            assertEquals(writesBeforeVersionChecks, catalogRepository.writeAttemptCount)

            val forgedUnarchivedVersion = unspecVersionOnOkFile.copy(archiveStatus = ArchiveStatus.UNARCHIVED)
            viewModel.requestPublishStudyFileVersion(forgedUnarchivedVersion)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, viewModel.uiState.value.feedback?.validationError)
            assertNull(viewModel.uiState.value.confirmationAction)

            viewModel.requestPublishStudyFileVersion(archivedVersionOnOkFile)
            assertEquals(
                AdminValidationError.VERSION_ARCHIVED_CANNOT_PUBLISH,
                viewModel.uiState.value.feedback?.validationError
            )
            assertNull(viewModel.uiState.value.confirmationAction)

            // Updating non-sensitive metadata on UNSPECIFIED version works cleanly.
            viewModel.saveStudyFileVersion(
                fileId = "file-ok",
                editing = unspecVersionOnOkFile,
                customVersionId = null,
                versionNumber = 1,
                versionLabel = "1.1",
                versionNotes = "ملاحظة تدقيق",
                publishAsActive = false
            )
            advanceUntilIdle()
            assertEquals(AdminSuccessMessage.VERSION_UPDATED, viewModel.uiState.value.feedback?.successMessage)
            assertEquals(writesBeforeVersionChecks + 1, catalogRepository.writeAttemptCount)
        }

    @Test
    fun `ArchiveStatus UNSPECIFIED propagates intact from Firestore mappers through ViewModel state`() =
        runTest {
            val mappedFile = com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogMapper.mapStudyFileForAdmin(
                id = "file-legacy-vm",
                data = mapOf(
                    "sectionId" to com.studentlearning.platform2026.data.remote.firebase.FirestoreReferenceTarget(
                        collectionId = "sections",
                        documentId = "sec-1",
                        path = "sections/sec-1"
                    ),
                    "title" to "ملف قديم",
                    "order" to 1,
                    "isPublished" to false,
                    "isArchived" to "false"
                ),
                expectedSectionId = "sec-1"
            )!!
            val mappedVersion = com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogMapper.mapStudyFileVersionForAdmin(
                id = "v1",
                data = mapOf(
                    "fileId" to com.studentlearning.platform2026.data.remote.firebase.FirestoreReferenceTarget(
                        collectionId = "study_files",
                        documentId = "file-legacy-vm",
                        path = "study_files/file-legacy-vm"
                    ),
                    "versionNumber" to 1,
                    "versionLabel" to "1.0",
                    "isPublished" to false
                ),
                expectedFileId = "file-legacy-vm"
            )!!
            assertEquals(ArchiveStatus.UNSPECIFIED, mappedFile.archiveStatus)
            assertEquals(ArchiveStatus.UNSPECIFIED, mappedVersion.archiveStatus)

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
                studyFiles.add(mappedFile)
                versions.add(mappedVersion)
            }
            val viewModel = AdminPortalViewModel(authRepository, catalogRepository)
            advanceUntilIdle()

            viewModel.openSectionFiles(
                com.studentlearning.platform2026.domain.model.StudySection("sec-1", "math", "القسم", 1, true)
            )
            advanceUntilIdle()
            assertEquals(ArchiveStatus.UNSPECIFIED, viewModel.uiState.value.studyFiles.single().archiveStatus)

            viewModel.openFileVersionsManager(viewModel.uiState.value.studyFiles.single())
            advanceUntilIdle()
            assertEquals(ArchiveStatus.UNSPECIFIED, viewModel.uiState.value.fileVersions.single().archiveStatus)

            val repoArchiveResult = catalogRepository.setStudyFileArchived("file-legacy-vm", true)
            assertTrue(repoArchiveResult is AppResult.Error)
            assertEquals(
                AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                ((repoArchiveResult as AppResult.Error).cause as AdminException).validationError
            )
        }

    @Test
    fun `requestToggleStudyFilePublished rejects file missing from loaded studyFiles with 0 repository writes`() =
        runTest {
            val setup = createOwnerViewModelWithFiles(
                files = listOf(
                    StudyFileMetadata(
                        id = "file-loaded",
                        sectionId = "sec-1",
                        title = "ملف محمل",
                        order = 1,
                        isPublished = false,
                        archiveStatus = ArchiveStatus.UNARCHIVED
                    )
                )
            )
            val repo = setup.second
            val vm = setup.third

            val missingFile = StudyFileMetadata(
                id = "file-not-in-uistate",
                sectionId = "sec-1",
                title = "ملف غائب",
                order = 2,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )

            vm.requestToggleStudyFilePublished(missingFile)
            advanceUntilIdle()

            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(true, vm.uiState.value.feedback?.isError)
            assertEquals(AdminFailureReason.NOT_FOUND, vm.uiState.value.feedback?.failureReason)
            assertEquals(0, repo.writeAttemptCount)
        }

    @Test
    fun `requestToggleStudyFileArchived rejects file missing from loaded studyFiles with 0 repository writes`() =
        runTest {
            val setup = createOwnerViewModelWithFiles(
                files = listOf(
                    StudyFileMetadata(
                        id = "file-loaded",
                        sectionId = "sec-1",
                        title = "ملف محمل",
                        order = 1,
                        isPublished = false,
                        archiveStatus = ArchiveStatus.UNARCHIVED
                    )
                )
            )
            val repo = setup.second
            val vm = setup.third

            val missingFile = StudyFileMetadata(
                id = "file-missing",
                sectionId = "sec-1",
                title = "ملف غير محمل",
                order = 2,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )

            vm.requestToggleStudyFileArchived(missingFile)
            advanceUntilIdle()

            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(true, vm.uiState.value.feedback?.isError)
            assertEquals(AdminFailureReason.NOT_FOUND, vm.uiState.value.feedback?.failureReason)
            assertEquals(0, repo.writeAttemptCount)
        }

    @Test
    fun `requestPublishStudyFileVersion rejects version missing from fileVersions wrong fileId or missing parent with 0 writes`() =
        runTest {
            val file1 = StudyFileMetadata(
                id = "file-1",
                sectionId = "sec-1",
                title = "ملف 1",
                order = 1,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val file2 = file1.copy(id = "file-2", title = "ملف 2", order = 2)
            val v1File1 = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-1/versions/v1/doc.pdf",
                fileSizeBytes = 4096L,
                checksumSha256 = "a".repeat(64),
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val setup = createOwnerViewModelWithFiles(
                files = listOf(file1, file2),
                versions = listOf(v1File1)
            )
            val repo = setup.second
            val vm = setup.third

            // 1. Missing parent dialog & version not loaded in fileVersions
            vm.requestPublishStudyFileVersion(v1File1)
            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(AdminFailureReason.NOT_FOUND, vm.uiState.value.feedback?.failureReason)

            // 2. Open versions manager for file1 -> fileVersions contains v1 only
            vm.openFileVersionsManager(file1)
            advanceUntilIdle()

            // Non-existent version id in fileVersions
            vm.requestPublishStudyFileVersion(v1File1.copy(id = "v-missing"))
            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(AdminFailureReason.NOT_FOUND, vm.uiState.value.feedback?.failureReason)

            // Version belonging to another fileId
            vm.requestPublishStudyFileVersion(v1File1.copy(fileId = "file-2"))
            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(
                AdminValidationError.EMPTY_PARENT_REFERENCE,
                vm.uiState.value.feedback?.validationError
            )

            // Dismiss versions manager and test missing parent outside dialog
            vm.dismissFileVersionsManager()
            vm.requestPublishStudyFileVersion(v1File1.copy(fileId = "file-absent"))
            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(
                AdminValidationError.EMPTY_PARENT_REFERENCE,
                vm.uiState.value.feedback?.validationError
            )
            assertEquals(0, repo.writeAttemptCount)
        }

    @Test
    fun `requestToggleStudyFilePublished allows unpublishing live UNSPECIFIED file present in studyFiles`() =
        runTest {
            val liveUnspecFile = StudyFileMetadata(
                id = "file-live-unspec",
                sectionId = "sec-1",
                title = "ملف قديم منشور",
                order = 1,
                isPublished = true,
                archiveStatus = ArchiveStatus.UNSPECIFIED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val setup = createOwnerViewModelWithFiles(files = listOf(liveUnspecFile))
            val repo = setup.second
            val vm = setup.third

            vm.requestToggleStudyFilePublished(liveUnspecFile)
            assertTrue(vm.uiState.value.confirmationAction is AdminConfirmationAction.UnpublishStudyFile)

            vm.confirmPendingAction()
            advanceUntilIdle()

            assertEquals(1, repo.writeAttemptCount)
            assertEquals("file-live-unspec" to false, repo.lastPublishToggle)
            assertFalse(repo.studyFiles.single().isPublished)
        }

    @Test
    fun `requestToggleStudyFilePublished rejects caller file with UNARCHIVED when studyFiles has UNSPECIFIED`() =
        runTest {
            val authoritativeUnspec = StudyFileMetadata(
                id = "file-unspec",
                sectionId = "sec-1",
                title = "ملف غير محدد",
                order = 1,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNSPECIFIED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val setup = createOwnerViewModelWithFiles(files = listOf(authoritativeUnspec))
            val repo = setup.second
            val vm = setup.third

            val staleUnarchivedArg = authoritativeUnspec.copy(archiveStatus = ArchiveStatus.UNARCHIVED)
            vm.requestToggleStudyFilePublished(staleUnarchivedArg)
            advanceUntilIdle()

            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(
                AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                vm.uiState.value.feedback?.validationError
            )
            assertEquals(0, repo.writeAttemptCount)
        }

    @Test
    fun `requestToggleStudyFileArchived rejects caller file with UNARCHIVED when studyFiles has UNSPECIFIED`() =
        runTest {
            val authoritativeUnspec = StudyFileMetadata(
                id = "file-unspec",
                sectionId = "sec-1",
                title = "ملف غير محدد",
                order = 1,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNSPECIFIED
            )
            val setup = createOwnerViewModelWithFiles(files = listOf(authoritativeUnspec))
            val repo = setup.second
            val vm = setup.third

            val staleUnarchivedArg = authoritativeUnspec.copy(archiveStatus = ArchiveStatus.UNARCHIVED)
            vm.requestToggleStudyFileArchived(staleUnarchivedArg)
            advanceUntilIdle()

            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(
                AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                vm.uiState.value.feedback?.validationError
            )
            assertEquals(0, repo.writeAttemptCount)
        }

    @Test
    fun `requestPublishStudyFileVersion rejects caller version with UNARCHIVED when fileVersions has UNSPECIFIED`() =
        runTest {
            val unarchivedParent = StudyFileMetadata(
                id = "file-1",
                sectionId = "sec-1",
                title = "ملف سليم",
                order = 1,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val authoritativeUnspecVersion = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-1/versions/v1/doc.pdf",
                fileSizeBytes = 4096L,
                checksumSha256 = "a".repeat(64),
                isPublished = false,
                archiveStatus = ArchiveStatus.UNSPECIFIED
            )
            val setup = createOwnerViewModelWithFiles(
                files = listOf(unarchivedParent),
                versions = listOf(authoritativeUnspecVersion)
            )
            val repo = setup.second
            val vm = setup.third

            vm.openFileVersionsManager(unarchivedParent)
            advanceUntilIdle()

            val staleUnarchivedVersion = authoritativeUnspecVersion.copy(archiveStatus = ArchiveStatus.UNARCHIVED)
            vm.requestPublishStudyFileVersion(staleUnarchivedVersion)
            advanceUntilIdle()

            assertNull(vm.uiState.value.confirmationAction)
            assertEquals(
                AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                vm.uiState.value.feedback?.validationError
            )
            assertEquals(0, repo.writeAttemptCount)
        }

    @Test
    fun `confirmPendingAction re-validates authoritative UiState and aborts with 0 writes if state turned UNSPECIFIED or disappeared`() =
        runTest {
            val validFile = StudyFileMetadata(
                id = "file-race",
                sectionId = "sec-1",
                title = "ملف سباق",
                order = 1,
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val validVersion = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-race",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-race/versions/v1/doc.pdf",
                fileSizeBytes = 4096L,
                checksumSha256 = "b".repeat(64),
                isPublished = false,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val setup = createOwnerViewModelWithFiles(
                files = listOf(validFile),
                versions = listOf(validVersion)
            )
            val repo = setup.second
            val vm = setup.third

            // Case A: Request archive confirmation while UNARCHIVED, then authoritative list reloads as UNSPECIFIED before confirm.
            vm.requestToggleStudyFileArchived(validFile)
            assertTrue(vm.uiState.value.confirmationAction is AdminConfirmationAction.ArchiveStudyFile)

            repo.studyFiles[0] = validFile.copy(archiveStatus = ArchiveStatus.UNSPECIFIED)
            vm.loadStudyFiles("sec-1")
            advanceUntilIdle()

            vm.confirmPendingAction()
            advanceUntilIdle()

            assertEquals(
                AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                vm.uiState.value.feedback?.validationError
            )
            assertEquals(0, repo.writeAttemptCount)

            // Case B: Request version publish while UNARCHIVED, then version becomes UNSPECIFIED before confirm.
            repo.studyFiles[0] = validFile.copy(archiveStatus = ArchiveStatus.UNARCHIVED)
            vm.loadStudyFiles("sec-1")
            advanceUntilIdle()
            vm.openFileVersionsManager(validFile)
            advanceUntilIdle()

            vm.requestPublishStudyFileVersion(validVersion)
            assertTrue(vm.uiState.value.confirmationAction is AdminConfirmationAction.PublishStudyFileVersion)

            repo.versions[0] = validVersion.copy(archiveStatus = ArchiveStatus.UNSPECIFIED)
            vm.loadFileVersions("file-race")
            advanceUntilIdle()

            vm.confirmPendingAction()
            advanceUntilIdle()

            assertEquals(
                AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                vm.uiState.value.feedback?.validationError
            )
            assertEquals(0, repo.writeAttemptCount)

            // Case C: Request archive confirmation, then section files clear/remove the file before confirm.
            repo.versions[0] = validVersion
            vm.requestToggleStudyFileArchived(validFile)
            assertTrue(vm.uiState.value.confirmationAction is AdminConfirmationAction.ArchiveStudyFile)

            repo.studyFiles.clear()
            vm.loadStudyFiles("sec-1")
            advanceUntilIdle()

            vm.confirmPendingAction()
            advanceUntilIdle()

            assertEquals(AdminFailureReason.NOT_FOUND, vm.uiState.value.feedback?.failureReason)
            assertEquals(0, repo.writeAttemptCount)
        }

    private fun kotlinx.coroutines.test.TestScope.createOwnerViewModelWithFiles(
        files: List<StudyFileMetadata>,
        versions: List<StudyFileVersionMetadata> = emptyList()
    ): Triple<FakeAuthRepository, FakeAdminCatalogRepository, AdminPortalViewModel> {
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
            brokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
            studyFiles.addAll(files)
            this.versions.addAll(versions)
        }
        val viewModel = AdminPortalViewModel(
            authRepository = authRepository,
            adminCatalogRepository = catalogRepository,
            r2BrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
        )
        advanceUntilIdle()
        viewModel.openSectionFiles(StudySection("sec-1", "math", "القسم", 1, true))
        advanceUntilIdle()
        return Triple(authRepository, catalogRepository, viewModel)
    }

    @Test
    fun `owner can load staff grant admin and revoke admin with confirmation while protecting owner and self`() =
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
            val catalogRepository = FakeAdminCatalogRepository()
            val governanceRepo = FakeAdminGovernanceRepository(authRepository).apply {
                staff.addAll(
                    listOf(
                        StaffMember(
                            uid = "owner-1",
                            email = "owner@example.com",
                            displayName = "المالك",
                            role = AdminRole.OWNER
                        ),
                        StaffMember(
                            uid = "admin-1",
                            email = "admin1@example.com",
                            displayName = "مشرف أول",
                            role = AdminRole.ADMIN
                        )
                    )
                )
            }
            val viewModel = AdminPortalViewModel(
                authRepository = authRepository,
                adminCatalogRepository = catalogRepository,
                adminGovernanceRepository = governanceRepo
            )
            advanceUntilIdle()

            // Switch to STAFF tab (Owner only)
            viewModel.selectTab(AdminManagementSection.STAFF)
            advanceUntilIdle()
            assertEquals(AdminManagementSection.STAFF, viewModel.uiState.value.activeTab)
            assertEquals(2, viewModel.uiState.value.staffMembers.size)

            // Cannot request revoke on Owner or self
            viewModel.requestRevokeAdminRole(governanceRepo.staff[0])
            assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, viewModel.uiState.value.feedback?.failureReason)
            assertNull(viewModel.uiState.value.confirmationAction)

            // Grant new admin
            viewModel.grantAdminRoleByEmail("newadmin@example.com")
            advanceUntilIdle()
            assertEquals(AdminSuccessMessage.STAFF_ADMIN_GRANTED, viewModel.uiState.value.feedback?.successMessage)
            assertTrue(viewModel.uiState.value.staffMembers.any { it.email == "newadmin@example.com" })
            assertTrue(viewModel.uiState.value.auditLogs.any { it.action == "ADMIN_ROLE_GRANTED" })

            // Revoke admin requires confirmation dialog before calling repository
            val targetAdmin = viewModel.uiState.value.staffMembers.first { it.uid == "admin-1" }
            val callsBeforeRevoke = governanceRepo.roleUpdateCalls
            viewModel.requestRevokeAdminRole(targetAdmin)
            assertTrue(viewModel.uiState.value.confirmationAction is AdminConfirmationAction.RevokeAdminRole)
            assertEquals(callsBeforeRevoke, governanceRepo.roleUpdateCalls)

            viewModel.confirmPendingAction()
            advanceUntilIdle()
            assertEquals(AdminSuccessMessage.STAFF_ADMIN_REVOKED, viewModel.uiState.value.feedback?.successMessage)
            assertFalse(viewModel.uiState.value.staffMembers.any { it.uid == "admin-1" })
            assertTrue(viewModel.uiState.value.auditLogs.any { it.action == "ADMIN_ROLE_REVOKED" })
        }

    @Test
    fun `non-owner admin is blocked from staff management while able to view audit logs and broadcast updates`() =
        runTest {
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
            val governanceRepo = FakeAdminGovernanceRepository(authRepository).apply {
                logs.add(
                    AuditLogEntry(
                        id = "log-1",
                        action = "BRANCH_CREATED",
                        actorUid = "owner-1",
                        actorRole = "owner",
                        targetCollection = "branches",
                        targetId = "scientific",
                        summary = "إنشاء الفرع العلمي",
                        source = "client",
                        createdAtEpochMillis = 1_700_000_000_000L
                    )
                )
            }
            val viewModel = AdminPortalViewModel(
                authRepository = authRepository,
                adminCatalogRepository = catalogRepository,
                adminGovernanceRepository = governanceRepo
            )
            advanceUntilIdle()

            // Non-owner cannot enter STAFF tab or execute staff mutations
            viewModel.selectTab(AdminManagementSection.STAFF)
            assertFalse(viewModel.uiState.value.activeTab == AdminManagementSection.STAFF)
            assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, viewModel.uiState.value.feedback?.failureReason)

            viewModel.grantAdminRoleByEmail("other@example.com")
            advanceUntilIdle()
            assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, viewModel.uiState.value.feedback?.failureReason)
            assertEquals(0, governanceRepo.roleUpdateCalls)

            // Non-owner CAN view audit logs and broadcast important updates
            viewModel.selectTab(AdminManagementSection.AUDIT_LOGS)
            advanceUntilIdle()
            assertEquals(AdminManagementSection.AUDIT_LOGS, viewModel.uiState.value.activeTab)
            assertEquals(listOf("log-1"), viewModel.uiState.value.auditLogs.map { it.id })

            governanceRepo.broadcastResultSent = false
            viewModel.broadcastImportantUpdate("تحديث عام", "تم إضافة ملخصات جديدة")
            advanceUntilIdle()
            assertEquals(
                AdminSuccessMessage.BROADCAST_RECORDED_PENDING_FCM,
                viewModel.uiState.value.feedback?.successMessage
            )
            assertTrue(viewModel.uiState.value.auditLogs.any { it.action == "IMPORTANT_UPDATE_BROADCAST" })

            governanceRepo.broadcastResultSent = true
            viewModel.broadcastImportantUpdate("تنبيه عاجل", "النسخة النهائية متوفرة الآن")
            advanceUntilIdle()
            assertEquals(
                AdminSuccessMessage.BROADCAST_SENT,
                viewModel.uiState.value.feedback?.successMessage
            )
        }
}
