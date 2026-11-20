package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminSession
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val sessionFlow: StateFlow<AdminSession>

    suspend fun refreshAdminSession(forceTokenRefresh: Boolean = true): AppResult<AdminSession>

    suspend fun signInWithEmailAndPassword(
        email: String,
        password: String
    ): AppResult<AdminSession>

    suspend fun createStudentAccount(
        email: String,
        password: String,
        confirmPassword: String
    ): AppResult<AdminSession>

    suspend fun sendPasswordResetEmail(email: String): AppResult<Unit>

    suspend fun signOut(): AppResult<Unit>

    /**
     * Ensures there is an active signed-in Firebase account (Student, Admin, or Owner).
     * Never grants administrative privileges.
     */
    suspend fun requireAuthenticatedSession(): AdminSession

    /**
     * Ensures there is a signed-in account with freshly confirmed Owner/Admin custom claims.
     */
    suspend fun requireAuthorizedAdminSession(): AdminSession
}
