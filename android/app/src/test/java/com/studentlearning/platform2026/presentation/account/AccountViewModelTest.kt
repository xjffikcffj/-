package com.studentlearning.platform2026.presentation.account

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `student sign-up succeeds with STUDENT role and surfaces success state`() = runTest {
        val authRepository = FakeAuthRepository()
        val viewModel = AccountViewModel(authRepository)

        viewModel.createStudentAccount("student@example.com", "Study2026Pass", "Study2026Pass")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.signUpSucceeded)
        assertTrue(state.session.isSignedIn)
        assertEquals(AppUserRole.STUDENT, state.session.userRole)
        assertFalse(state.session.hasRecognizedAdminRole)
    }

    @Test
    fun `sign-up surfaces EMAIL_ALREADY_IN_USE error and prevents duplicate parallel submissions`() = runTest {
        val authRepository = FakeAuthRepository()
        val gate = CompletableDeferred<Unit>()
        var signUpAttempts = 0
        authRepository.signUpHandler = { _, _, _ ->
            signUpAttempts++
            gate.await()
            AppResult.Error(
                "Already used",
                AdminException(AdminFailureReason.EMAIL_ALREADY_IN_USE)
            )
        }
        val viewModel = AccountViewModel(authRepository)

        viewModel.createStudentAccount("used@example.com", "Study2026Pass", "Study2026Pass")
        viewModel.createStudentAccount("used@example.com", "Study2026Pass", "Study2026Pass")
        assertTrue(viewModel.uiState.value.isSubmittingSignUp)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, signUpAttempts)
        assertFalse(viewModel.uiState.value.isSubmittingSignUp)
        assertFalse(viewModel.uiState.value.signUpSucceeded)
        assertEquals(AdminFailureReason.EMAIL_ALREADY_IN_USE, viewModel.uiState.value.signUpErrorReason)
    }

    @Test
    fun `password recovery sends email and handles validation error`() = runTest {
        val authRepository = FakeAuthRepository()
        authRepository.passwordResetHandler = { email ->
            if (!email.contains("@")) {
                AppResult.Error(
                    "Invalid email",
                    AdminException(AdminFailureReason.VALIDATION_ERROR, AdminValidationError.INVALID_EMAIL)
                )
            } else {
                AppResult.Success(Unit)
            }
        }
        val viewModel = AccountViewModel(authRepository)

        viewModel.sendPasswordResetEmail("bad-email")
        advanceUntilIdle()
        assertEquals(AdminFailureReason.VALIDATION_ERROR, viewModel.uiState.value.passwordResetErrorReason)
        assertEquals(AdminValidationError.INVALID_EMAIL, viewModel.uiState.value.passwordResetValidationError)

        viewModel.sendPasswordResetEmail("  student@example.com ")
        advanceUntilIdle()
        assertEquals("student@example.com", viewModel.uiState.value.passwordResetEmailSentTo)
        assertEquals("  student@example.com ", authRepository.lastResetEmailRequested)
    }

    @Test
    fun `switching from owner to student via signIn does not retain owner role`() = runTest {
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
        authRepository.signInHandler = { email, _ ->
            AppResult.Success(
                AdminSession(
                    isSignedIn = true,
                    uid = "student-9",
                    email = email,
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
        }
        val viewModel = AccountViewModel(authRepository)
        assertEquals(AppUserRole.OWNER, viewModel.uiState.value.session.userRole)

        viewModel.signIn("student@example.com", "Study2026Pass")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AppUserRole.STUDENT, state.session.userRole)
        assertFalse(state.session.hasRecognizedAdminRole)
        assertFalse(state.session.canPerformAdminActions)
    }

    @Test
    fun `signOut resets session to Guest and updates account status message`() = runTest {
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
        val viewModel = AccountViewModel(authRepository)

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(1, authRepository.signOutCalls)
        assertEquals(AppUserRole.GUEST, viewModel.uiState.value.session.userRole)
        assertEquals(AccountStatusMessage.SIGNED_OUT, viewModel.uiState.value.accountStatusMessage)
    }
}
