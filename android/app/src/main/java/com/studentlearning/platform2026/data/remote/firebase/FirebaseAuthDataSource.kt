package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.auth.FirebaseAuth
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Claims
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class AuthUserSnapshot(
    val isSignedIn: Boolean,
    val uid: String? = null,
    val email: String? = null
)

interface AuthTokenProvider {
    suspend fun getIdTokenOrNull(forceRefresh: Boolean = false): String?
}

interface AuthRemoteDataSource {
    fun observeAuthUserState(): Flow<AuthUserSnapshot>
    fun getCurrentSignedInUser(): AuthUserSnapshot
    suspend fun readCurrentSession(forceTokenRefresh: Boolean): AdminSession
    suspend fun signInWithEmailAndPassword(email: String, password: String): AdminSession
    suspend fun createStudentAccountWithEmailAndPassword(email: String, password: String): AdminSession
    suspend fun sendPasswordResetEmail(email: String)
    suspend fun signOut()
}

class FirebaseAuthDataSource(
    private val authProvider: FirebaseAuthProvider
) : AuthRemoteDataSource, AuthTokenProvider {

    override fun observeAuthUserState(): Flow<AuthUserSnapshot> = callbackFlow {
        val auth = authProvider.getAuthOrNull()
        if (auth == null) {
            trySend(AuthUserSnapshot(isSignedIn = false))
            close()
            return@callbackFlow
        }

        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            trySend(
                if (user == null) {
                    AuthUserSnapshot(isSignedIn = false)
                } else {
                    AuthUserSnapshot(
                        isSignedIn = true,
                        uid = user.uid,
                        email = user.email
                    )
                }
            )
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override fun getCurrentSignedInUser(): AuthUserSnapshot {
        val user = authProvider.getAuthOrNull()?.currentUser ?: return AuthUserSnapshot(isSignedIn = false)
        return AuthUserSnapshot(
            isSignedIn = true,
            uid = user.uid,
            email = user.email
        )
    }

    override suspend fun readCurrentSession(forceTokenRefresh: Boolean): AdminSession {
        val auth = requireConfiguredAuth()
        val currentUser = auth.currentUser ?: return AdminSession()
        // NOTE: Firebase Auth SDK decodes token claims locally; cryptographic signature
        // verification and enforcement occur on Firebase servers / Firestore Security Rules.
        val tokenResult = currentUser.getIdToken(forceTokenRefresh).awaitResult()
        val role = AdminRole.fromClaim(tokenResult.claims[Claims.ROLE])

        return AdminSession(
            isSignedIn = true,
            uid = currentUser.uid,
            email = currentUser.email,
            role = role,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = forceTokenRefresh
        )
    }

    override suspend fun signInWithEmailAndPassword(email: String, password: String): AdminSession {
        val auth = requireConfiguredAuth()
        auth.signInWithEmailAndPassword(email, password).awaitResult()
        // Always force token refresh after sign-in to read up-to-date custom claims.
        return readCurrentSession(forceTokenRefresh = true)
    }

    override suspend fun createStudentAccountWithEmailAndPassword(
        email: String,
        password: String
    ): AdminSession {
        val auth = requireConfiguredAuth()
        auth.createUserWithEmailAndPassword(email, password).awaitResult()
        // New Email/Password student accounts have no elevated custom claims; client never writes claims.
        return readCurrentSession(forceTokenRefresh = true)
    }

    override suspend fun sendPasswordResetEmail(email: String) {
        val auth = requireConfiguredAuth()
        auth.sendPasswordResetEmail(email).awaitResult()
    }

    override suspend fun signOut() {
        authProvider.getAuthOrNull()?.signOut()
    }

    override suspend fun getIdTokenOrNull(forceRefresh: Boolean): String? {
        val currentUser = authProvider.getAuthOrNull()?.currentUser ?: return null
        if (currentUser.isAnonymous) return null
        val tokenResult = currentUser.getIdToken(forceRefresh).awaitResult()
        return tokenResult.token?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun requireConfiguredAuth(): FirebaseAuth {
        return authProvider.getAuthOrNull()
            ?: throw AdminException(
                reason = AdminFailureReason.FIREBASE_NOT_CONFIGURED,
                message = "Firebase Authentication is not configured locally yet."
            )
    }
}
