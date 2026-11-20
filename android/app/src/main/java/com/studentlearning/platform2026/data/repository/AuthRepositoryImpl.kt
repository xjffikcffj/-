package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.AuthRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthUserSnapshot
import com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogMapper
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.validation.AuthValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AuthRepositoryImpl(
    private val remoteDataSource: AuthRemoteDataSource,
    private val dispatcherProvider: DispatcherProvider,
    observerScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
) : AuthRepository {

    private val sessionMutex = Mutex()
    private val _sessionFlow = MutableStateFlow(AdminSession())
    override val sessionFlow: StateFlow<AdminSession> = _sessionFlow.asStateFlow()

    init {
        observerScope.launch {
            remoteDataSource.observeAuthUserState().collect { snapshot ->
                handleAuthStateSnapshot(snapshot)
            }
        }
    }

    override suspend fun refreshAdminSession(forceTokenRefresh: Boolean): AppResult<AdminSession> {
        return withContext(dispatcherProvider.io) {
            sessionMutex.withLock {
                try {
                    val previous = _sessionFlow.value
                    val remoteSession = remoteDataSource.readCurrentSession(forceTokenRefresh)
                    val resolvedSession = if (!remoteSession.isSignedIn || remoteSession.uid.isNullOrBlank()) {
                        AdminSession()
                    } else {
                        val sameAccount = previous.isSignedIn && previous.uid == remoteSession.uid
                        val confirmed = forceTokenRefresh || (sameAccount && previous.areClaimsConfirmedCurrent)
                        remoteSession.copy(areClaimsConfirmedCurrent = confirmed)
                    }
                    _sessionFlow.value = resolvedSession
                    AppResult.Success(resolvedSession)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val adminError = toAdminException(throwable)
                    preserveSignedInUserWithUnconfirmedClaimsOnError(adminError)
                    AppResult.Error(
                        message = adminError.message ?: "Failed to refresh auth session",
                        cause = adminError
                    )
                }
            }
        }
    }

    override suspend fun signInWithEmailAndPassword(
        email: String,
        password: String
    ): AppResult<AdminSession> {
        return withContext(dispatcherProvider.io) {
            sessionMutex.withLock {
                val previous = _sessionFlow.value
                try {
                    val (validEmail, validPassword) = AuthValidator.validateSignIn(email, password)
                    // Immediately clear any prior account's claims before switching or signing in.
                    _sessionFlow.value = AdminSession()
                    val session = remoteDataSource.signInWithEmailAndPassword(validEmail, validPassword)
                    _sessionFlow.value = session
                    AppResult.Success(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val adminError = toAdminException(throwable)
                    restoreOrSanitizeSessionAfterAuthFailure(previous = previous, adminError = adminError)
                    AppResult.Error(
                        message = adminError.message ?: "Sign-in failed",
                        cause = adminError
                    )
                }
            }
        }
    }

    override suspend fun createStudentAccount(
        email: String,
        password: String,
        confirmPassword: String
    ): AppResult<AdminSession> {
        return withContext(dispatcherProvider.io) {
            sessionMutex.withLock {
                val previous = _sessionFlow.value
                try {
                    val (validEmail, validPassword) = AuthValidator.validateStudentSignUp(
                        email = email,
                        password = password,
                        confirmPassword = confirmPassword
                    )
                    // Clear any previous session state before registering a new student account.
                    _sessionFlow.value = AdminSession()
                    val created = remoteDataSource.createStudentAccountWithEmailAndPassword(
                        email = validEmail,
                        password = validPassword
                    )
                    // Client-side registration must strictly default to Student (no admin/owner role).
                    val studentSession = created.copy(role = AdminRole.NONE)
                    _sessionFlow.value = studentSession
                    AppResult.Success(studentSession)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val adminError = toAdminException(throwable)
                    restoreOrSanitizeSessionAfterAuthFailure(previous = previous, adminError = adminError)
                    AppResult.Error(
                        message = adminError.message ?: "Account registration failed",
                        cause = adminError
                    )
                }
            }
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): AppResult<Unit> {
        return withContext(dispatcherProvider.io) {
            try {
                val validEmail = AuthValidator.validatePasswordReset(email)
                remoteDataSource.sendPasswordResetEmail(validEmail)
                AppResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val adminError = toAdminException(throwable)
                AppResult.Error(
                    message = adminError.message ?: "Password reset request failed",
                    cause = adminError
                )
            }
        }
    }

    override suspend fun signOut(): AppResult<Unit> {
        return withContext(dispatcherProvider.io) {
            sessionMutex.withLock {
                try {
                    // Clear authentication session only; device-local preferences & data remain untouched.
                    _sessionFlow.value = AdminSession()
                    remoteDataSource.signOut()
                    AppResult.Success(Unit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val adminError = toAdminException(throwable)
                    AppResult.Error(
                        message = adminError.message ?: "Sign-out failed",
                        cause = adminError
                    )
                }
            }
        }
    }

    override suspend fun requireAuthenticatedSession(): AdminSession {
        return withContext(dispatcherProvider.io) {
            sessionMutex.withLock {
                val currentUser = safeGetCurrentSignedInUser()
                if (!currentUser.isSignedIn || currentUser.uid.isNullOrBlank()) {
                    _sessionFlow.value = AdminSession()
                    throw AdminException(
                        reason = AdminFailureReason.UNAUTHENTICATED,
                        message = "User sign-in is required."
                    )
                }

                val previous = _sessionFlow.value
                if (previous.isSignedIn && previous.uid == currentUser.uid) {
                    previous
                } else {
                    val studentSnapshot = AdminSession(
                        isSignedIn = true,
                        uid = currentUser.uid,
                        email = currentUser.email,
                        role = AdminRole.NONE,
                        hasLoadedTokenClaims = false,
                        areClaimsConfirmedCurrent = false
                    )
                    _sessionFlow.value = studentSnapshot
                    studentSnapshot
                }
            }
        }
    }

    override suspend fun requireAuthorizedAdminSession(): AdminSession {
        return withContext(dispatcherProvider.io) {
            sessionMutex.withLock {
                val previous = _sessionFlow.value
                val currentUser = safeGetCurrentSignedInUser()

                if (!currentUser.isSignedIn || currentUser.uid.isNullOrBlank()) {
                    _sessionFlow.value = AdminSession()
                    throw AdminException(
                        reason = AdminFailureReason.UNAUTHENTICATED,
                        message = "Admin sign-in is required."
                    )
                }

                // If the signed-in UID changed since our last cached session, wipe old claims immediately.
                val accountSwitched = previous.uid != null && previous.uid != currentUser.uid
                if (accountSwitched) {
                    _sessionFlow.value = AdminSession(
                        isSignedIn = true,
                        uid = currentUser.uid,
                        email = currentUser.email,
                        role = AdminRole.NONE,
                        hasLoadedTokenClaims = false,
                        areClaimsConfirmedCurrent = false
                    )
                }

                val currentState = _sessionFlow.value
                // If previous refresh failed or claims weren't confirmed current, refuse stale claims
                // and require a live forced token refresh to succeed before proceeding.
                val shouldForceRefresh = accountSwitched ||
                    !currentState.hasLoadedTokenClaims ||
                    !currentState.areClaimsConfirmedCurrent

                val session = try {
                    remoteDataSource.readCurrentSession(forceTokenRefresh = shouldForceRefresh)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val adminError = toAdminException(throwable)
                    preserveSignedInUserWithUnconfirmedClaimsOnError(adminError)
                    throw adminError
                }

                val normalizedSession = if (shouldForceRefresh) {
                    session
                } else {
                    session.copy(areClaimsConfirmedCurrent = currentState.areClaimsConfirmedCurrent)
                }
                _sessionFlow.value = normalizedSession

                if (!normalizedSession.isSignedIn || normalizedSession.uid.isNullOrBlank()) {
                    throw AdminException(
                        reason = AdminFailureReason.UNAUTHENTICATED,
                        message = "Admin sign-in is required."
                    )
                }
                if (!normalizedSession.hasLoadedTokenClaims || !normalizedSession.areClaimsConfirmedCurrent) {
                    throw AdminException(
                        reason = AdminFailureReason.SESSION_VERIFICATION_REQUIRED,
                        message = "Session claims must be refreshed before executing admin actions."
                    )
                }
                if (!normalizedSession.role.canAccessAdminDashboard) {
                    throw AdminException(
                        reason = AdminFailureReason.INSUFFICIENT_ROLE,
                        message = "Account does not have an owner or admin role claim."
                    )
                }
                normalizedSession
            }
        }
    }

    private suspend fun handleAuthStateSnapshot(snapshot: AuthUserSnapshot) {
        sessionMutex.withLock {
            if (!snapshot.isSignedIn || snapshot.uid.isNullOrBlank()) {
                _sessionFlow.value = AdminSession()
                return
            }

            val current = _sessionFlow.value
            if (current.uid != snapshot.uid || !current.isSignedIn) {
                // Account signed in or switched externally: wipe previous privileges immediately.
                _sessionFlow.value = AdminSession(
                    isSignedIn = true,
                    uid = snapshot.uid,
                    email = snapshot.email,
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = false,
                    areClaimsConfirmedCurrent = false
                )
                try {
                    val refreshed = remoteDataSource.readCurrentSession(forceTokenRefresh = true)
                    _sessionFlow.value = refreshed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    val adminError = toAdminException(throwable)
                    preserveSignedInUserWithUnconfirmedClaimsOnError(adminError)
                }
            }
        }
    }

    private suspend fun restoreOrSanitizeSessionAfterAuthFailure(
        previous: AdminSession,
        adminError: AdminException
    ) {
        if (adminError.reason == AdminFailureReason.FIREBASE_NOT_CONFIGURED) {
            _sessionFlow.value = AdminSession()
            return
        }

        val activeUser = safeGetCurrentSignedInUser()
        if (!activeUser.isSignedIn || activeUser.uid.isNullOrBlank()) {
            _sessionFlow.value = AdminSession()
            return
        }

        if (previous.isSignedIn && previous.uid == activeUser.uid) {
            // Sign-in/sign-up attempt failed before Firebase switched users; keep prior user's state intact.
            _sessionFlow.value = previous
        } else {
            // Firebase switched to a different user before token verification failed: never inherit old claims.
            _sessionFlow.value = AdminSession(
                isSignedIn = true,
                uid = activeUser.uid,
                email = activeUser.email,
                role = AdminRole.NONE,
                hasLoadedTokenClaims = false,
                areClaimsConfirmedCurrent = false
            )
        }
    }

    private suspend fun preserveSignedInUserWithUnconfirmedClaimsOnError(adminError: AdminException) {
        if (
            adminError.reason == AdminFailureReason.FIREBASE_NOT_CONFIGURED ||
            adminError.reason == AdminFailureReason.ACCOUNT_DISABLED ||
            adminError.reason == AdminFailureReason.ACCOUNT_NOT_FOUND ||
            adminError.reason == AdminFailureReason.UNAUTHENTICATED
        ) {
            _sessionFlow.value = AdminSession()
            if (adminError.reason != AdminFailureReason.FIREBASE_NOT_CONFIGURED) {
                runCatching { remoteDataSource.signOut() }
            }
            return
        }

        val currentUser = safeGetCurrentSignedInUser()
        if (!currentUser.isSignedIn || currentUser.uid.isNullOrBlank()) {
            _sessionFlow.value = AdminSession()
            return
        }

        val previous = _sessionFlow.value
        _sessionFlow.value = if (previous.isSignedIn && previous.uid == currentUser.uid) {
            // Preserve signed-in display state for the same user on transient/network errors,
            // while revoking mutation eligibility until a token refresh succeeds.
            previous.copy(
                email = currentUser.email ?: previous.email,
                areClaimsConfirmedCurrent = false
            )
        } else {
            // Never carry over claims from a different UID.
            AdminSession(
                isSignedIn = true,
                uid = currentUser.uid,
                email = currentUser.email,
                role = AdminRole.NONE,
                hasLoadedTokenClaims = false,
                areClaimsConfirmedCurrent = false
            )
        }
    }

    private fun safeGetCurrentSignedInUser(): AuthUserSnapshot {
        return try {
            remoteDataSource.getCurrentSignedInUser()
        } catch (_: Throwable) {
            AuthUserSnapshot(isSignedIn = false)
        }
    }

    private fun toAdminException(throwable: Throwable): AdminException {
        if (throwable is AdminException) return throwable
        val nestedAdmin = throwable.cause as? AdminException
        if (nestedAdmin != null) return nestedAdmin
        return AdminException(
            reason = FirestoreAdminCatalogMapper.mapThrowableToAdminReason(throwable),
            message = throwable.localizedMessage,
            cause = throwable
        )
    }
}
