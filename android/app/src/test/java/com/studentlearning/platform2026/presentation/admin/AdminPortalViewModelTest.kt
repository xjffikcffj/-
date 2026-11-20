package com.studentlearning.platform2026.presentation.admin

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
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
}
