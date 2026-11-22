package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.AuthRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthUserSnapshot
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    @Test
    fun `owner sign-in succeeds and sets confirmed admin session`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        remote.signInHandler = { email, _ ->
            remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "owner-1", email = email)
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = email,
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)

        val result = repository.signInWithEmailAndPassword("owner@example.com", "secret-pass")

        assertTrue(result is AppResult.Success)
        val session = repository.sessionFlow.value
        assertTrue(session.isSignedIn)
        assertTrue(session.hasLoadedTokenClaims)
        assertTrue(session.areClaimsConfirmedCurrent)
        assertTrue(session.hasRecognizedAdminRole)
        assertTrue(session.canPerformAdminActions)
        assertEquals(AdminRole.OWNER, session.role)
        assertEquals(AppUserRole.OWNER, session.userRole)
    }

    @Test
    fun `createStudentAccount succeeds with STUDENT default role and never elevates privileges`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        remote.signUpHandler = { email, _ ->
            remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "stu-1", email = email)
            // Even if an upstream/unexpected snapshot carried an elevated role, repository enforces NONE on student sign-up.
            AdminSession(
                isSignedIn = true,
                uid = "stu-1",
                email = email,
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)

        val result = repository.createStudentAccount(
            email = "  student@example.com ",
            password = "Study2026Pass",
            confirmPassword = "Study2026Pass"
        )

        assertTrue(result is AppResult.Success)
        val session = repository.sessionFlow.value
        assertTrue(session.isSignedIn)
        assertEquals("student@example.com", session.email)
        assertEquals(AdminRole.NONE, session.role)
        assertEquals(AppUserRole.STUDENT, session.userRole)
        assertTrue(session.canDownloadOfflineFiles)
        assertFalse(session.hasRecognizedAdminRole)
        assertFalse(session.canPerformAdminActions)

        // Student passes requireAuthenticatedSession but fails requireAuthorizedAdminSession.
        val authSession = repository.requireAuthenticatedSession()
        assertEquals("stu-1", authSession.uid)

        remote.readSessionHandler = {
            AdminSession(
                isSignedIn = true,
                uid = "stu-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        }
        val adminFailure = try {
            repository.requireAuthorizedAdminSession()
            null
        } catch (ex: AdminException) {
            ex
        }
        assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, adminFailure?.reason)
    }

    @Test
    fun `createStudentAccount rejects invalid input and handles email already in use`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)

        val invalidRes = repository.createStudentAccount("bad-email", "short", "mismatch")
        assertTrue(invalidRes is AppResult.Error)
        val invalidEx = (invalidRes as AppResult.Error).cause as AdminException
        assertEquals(AdminFailureReason.VALIDATION_ERROR, invalidEx.reason)
        assertEquals(AdminValidationError.INVALID_EMAIL, invalidEx.validationError)

        remote.signUpHandler = { _, _ ->
            throw AdminException(AdminFailureReason.EMAIL_ALREADY_IN_USE)
        }
        val duplicateRes = repository.createStudentAccount("used@example.com", "Study2026Pass", "Study2026Pass")
        assertTrue(duplicateRes is AppResult.Error)
        assertEquals(
            AdminFailureReason.EMAIL_ALREADY_IN_USE,
            ((duplicateRes as AppResult.Error).cause as AdminException).reason
        )
        assertFalse(repository.sessionFlow.value.isSignedIn)
    }

    @Test
    fun `sendPasswordResetEmail validates email and calls remote data source`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)

        val invalid = repository.sendPasswordResetEmail("bad")
        assertTrue(invalid is AppResult.Error)
        assertEquals(
            AdminValidationError.INVALID_EMAIL,
            ((invalid as AppResult.Error).cause as AdminException).validationError
        )
        assertEquals(null, remote.lastResetEmail)

        val valid = repository.sendPasswordResetEmail(" student@example.com ")
        assertTrue(valid is AppResult.Success)
        assertEquals("student@example.com", remote.lastResetEmail)
    }

    @Test
    fun `regular signed-in user is not authorized and requireAuthorizedAdminSession throws`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        remote.signInHandler = { email, _ ->
            remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "student-1", email = email)
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = email,
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        }
        remote.readSessionHandler = {
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)

        val signInResult = repository.signInWithEmailAndPassword("student@example.com", "pass123")
        assertTrue(signInResult is AppResult.Success)
        assertFalse(repository.sessionFlow.value.hasRecognizedAdminRole)
        assertFalse(repository.sessionFlow.value.canPerformAdminActions)

        val failure = try {
            repository.requireAuthorizedAdminSession()
            null
        } catch (ex: AdminException) {
            ex
        }
        assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, failure?.reason)
    }

    @Test
    fun `network failure during token refresh preserves signed-in state with stale claims and blocks execution`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        val ownerSession = AdminSession(
            isSignedIn = true,
            uid = "owner-1",
            email = "owner@example.com",
            role = AdminRole.OWNER,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        remote.signInHandler = { _, _ ->
            remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "owner-1", email = "owner@example.com")
            ownerSession
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)
        repository.signInWithEmailAndPassword("owner@example.com", "secret-pass")

        // Simulate transient network loss during forced ID token refresh.
        remote.readSessionHandler = { throw IOException("Network offline") }
        val refreshResult = repository.refreshAdminSession(forceTokenRefresh = true)

        assertTrue(refreshResult is AppResult.Error)
        assertEquals(AdminFailureReason.NETWORK_UNAVAILABLE, ((refreshResult as AppResult.Error).cause as AdminException).reason)

        // Signed-in user & role metadata remain intact for display, while mutations are blocked.
        val staleSession = repository.sessionFlow.value
        assertTrue(staleSession.isSignedIn)
        assertEquals("owner-1", staleSession.uid)
        assertEquals("owner@example.com", staleSession.email)
        assertEquals(AdminRole.OWNER, staleSession.role)
        assertTrue(staleSession.hasLoadedTokenClaims)
        assertTrue(staleSession.hasRecognizedAdminRole)
        assertFalse(staleSession.areClaimsConfirmedCurrent)
        assertFalse(staleSession.canPerformAdminActions)

        // Repository execution guard must force a fresh refresh and refuse offline stale claims.
        val execError = try {
            repository.requireAuthorizedAdminSession()
            null
        } catch (ex: AdminException) {
            ex
        }
        assertEquals(AdminFailureReason.NETWORK_UNAVAILABLE, execError?.reason)
        assertEquals(true, remote.lastForceTokenRefresh)

        // Once network returns, a fresh token check re-enables confirmed admin actions.
        remote.readSessionHandler = { ownerSession }
        val restored = repository.requireAuthorizedAdminSession()
        assertTrue(restored.canPerformAdminActions)
        assertTrue(repository.sessionFlow.value.areClaimsConfirmedCurrent)
    }

    @Test
    fun `signOut immediately clears session and admin claims`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        remote.signInHandler = { _, _ ->
            remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "owner-1", email = "owner@example.com")
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)
        repository.signInWithEmailAndPassword("owner@example.com", "secret-pass")

        val result = repository.signOut()

        assertTrue(result is AppResult.Success)
        assertEquals(1, remote.signOutCalls)
        assertEquals(AdminSession(), repository.sessionFlow.value)
        assertFalse(repository.sessionFlow.value.isSignedIn)
        assertEquals(AppUserRole.GUEST, repository.sessionFlow.value.userRole)
        assertFalse(repository.sessionFlow.value.hasRecognizedAdminRole)
        assertFalse(repository.sessionFlow.value.canPerformAdminActions)
    }

    @Test
    fun `auth state listener account switch wipes previous owner privileges even if refresh fails`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        remote.signInHandler = { _, _ ->
            remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "owner-1", email = "owner@example.com")
            AdminSession(
                isSignedIn = true,
                uid = "owner-1",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        }
        val observerScope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + testDispatchers.io)
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = observerScope)
        advanceUntilIdle()
        repository.signInWithEmailAndPassword("owner@example.com", "secret-pass")
        assertTrue(repository.sessionFlow.value.canPerformAdminActions)

        // Switch account externally while offline so new user's token refresh fails.
        remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "student-2", email = "student2@example.com")
        remote.readSessionHandler = { throw IOException("Offline during account switch") }
        remote.emitAuthState(remote.currentUser)
        advanceUntilIdle()

        val switchedSession = repository.sessionFlow.value
        assertTrue(switchedSession.isSignedIn)
        assertEquals("student-2", switchedSession.uid)
        assertEquals("student2@example.com", switchedSession.email)
        assertEquals(AdminRole.NONE, switchedSession.role)
        assertEquals(AppUserRole.STUDENT, switchedSession.userRole)
        assertFalse(switchedSession.hasLoadedTokenClaims)
        assertFalse(switchedSession.areClaimsConfirmedCurrent)
        assertFalse(switchedSession.hasRecognizedAdminRole)
        assertFalse(switchedSession.canPerformAdminActions)

        // External sign-out wipes session completely.
        remote.currentUser = AuthUserSnapshot(isSignedIn = false)
        remote.emitAuthState(remote.currentUser)
        advanceUntilIdle()

        assertEquals(AdminSession(), repository.sessionFlow.value)
    }

    @Test
    fun `disabled or deleted account clears cached owner session and signs out locally`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        val ownerSession = AdminSession(
            isSignedIn = true,
            uid = "owner-1",
            email = "owner@example.com",
            role = AdminRole.OWNER,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        remote.signInHandler = { _, _ ->
            remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "owner-1", email = "owner@example.com")
            ownerSession
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)
        repository.signInWithEmailAndPassword("owner@example.com", "secret-pass")

        remote.readSessionHandler = { throw AdminException(AdminFailureReason.ACCOUNT_DISABLED) }
        val refreshResult = repository.refreshAdminSession(forceTokenRefresh = true)

        assertTrue(refreshResult is AppResult.Error)
        assertEquals(AdminFailureReason.ACCOUNT_DISABLED, ((refreshResult as AppResult.Error).cause as AdminException).reason)
        assertEquals(AdminSession(), repository.sessionFlow.value)
        assertEquals(AppUserRole.GUEST, repository.sessionFlow.value.userRole)
        assertEquals(1, remote.signOutCalls)
    }

    @Test
    fun `failed sign-in while owner is active preserves current owner session instead of corrupting state`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        val ownerSession = AdminSession(
            isSignedIn = true,
            uid = "owner-1",
            email = "owner@example.com",
            role = AdminRole.OWNER,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        remote.signInHandler = { email, _ ->
            if (email == "owner@example.com") {
                remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "owner-1", email = email)
                ownerSession
            } else {
                throw AdminException(AdminFailureReason.INVALID_CREDENTIALS)
            }
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)
        repository.signInWithEmailAndPassword("owner@example.com", "secret-pass")

        val failedSwitch = repository.signInWithEmailAndPassword("other@example.com", "wrong-pass")
        assertTrue(failedSwitch is AppResult.Error)
        assertEquals("owner-1", repository.sessionFlow.value.uid)
        assertEquals(AdminRole.OWNER, repository.sessionFlow.value.role)
        assertTrue(repository.sessionFlow.value.canPerformAdminActions)
    }

    @Test
    fun `owner signOut followed by student signIn drops all owner privileges`() = runTest {
        val remote = FakeAuthRemoteDataSource()
        remote.signInHandler = { email, _ ->
            if (email == "owner@example.com") {
                remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "owner-1", email = email)
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-1",
                    email = email,
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            } else {
                remote.currentUser = AuthUserSnapshot(isSignedIn = true, uid = "student-1", email = email)
                AdminSession(
                    isSignedIn = true,
                    uid = "student-1",
                    email = email,
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            }
        }
        val repository = AuthRepositoryImpl(remote, testDispatchers, observerScope = backgroundScope)
        repository.signInWithEmailAndPassword("owner@example.com", "secret-pass")
        assertEquals(AppUserRole.OWNER, repository.sessionFlow.value.userRole)

        repository.signOut()
        assertEquals(AppUserRole.GUEST, repository.sessionFlow.value.userRole)

        repository.signInWithEmailAndPassword("student@example.com", "Study2026Pass")
        val studentSession = repository.sessionFlow.value
        assertEquals(AppUserRole.STUDENT, studentSession.userRole)
        assertEquals(AdminRole.NONE, studentSession.role)
        assertFalse(studentSession.hasRecognizedAdminRole)
        assertFalse(studentSession.canPerformAdminActions)
    }

    private class FakeAuthRemoteDataSource : AuthRemoteDataSource {
        private val authStateFlow = MutableSharedFlow<AuthUserSnapshot>(extraBufferCapacity = 8)

        var currentUser: AuthUserSnapshot = AuthUserSnapshot(isSignedIn = false)
        var signOutCalls: Int = 0
        var lastForceTokenRefresh: Boolean? = null
        var lastResetEmail: String? = null

        var readSessionHandler: suspend (Boolean) -> AdminSession = { AdminSession() }
        var signInHandler: suspend (String, String) -> AdminSession = { _, _ -> AdminSession() }
        var signUpHandler: suspend (String, String) -> AdminSession = { _, _ -> AdminSession() }
        var passwordResetHandler: suspend (String) -> Unit = {}

        suspend fun emitAuthState(snapshot: AuthUserSnapshot) {
            authStateFlow.emit(snapshot)
        }

        override fun observeAuthUserState(): Flow<AuthUserSnapshot> = authStateFlow

        override fun getCurrentSignedInUser(): AuthUserSnapshot = currentUser

        override suspend fun readCurrentSession(forceTokenRefresh: Boolean): AdminSession {
            lastForceTokenRefresh = forceTokenRefresh
            return readSessionHandler(forceTokenRefresh)
        }

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AdminSession {
            return signInHandler(email, password)
        }

        override suspend fun createStudentAccountWithEmailAndPassword(
            email: String,
            password: String
        ): AdminSession {
            return signUpHandler(email, password)
        }

        override suspend fun sendPasswordResetEmail(email: String) {
            lastResetEmail = email
            passwordResetHandler(email)
        }

        override suspend fun signOut() {
            signOutCalls++
            currentUser = AuthUserSnapshot(isSignedIn = false)
        }
    }
}
