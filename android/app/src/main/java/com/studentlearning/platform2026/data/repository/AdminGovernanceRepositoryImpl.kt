package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.backend.BackendAdminVersionDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogMapper
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.AuditLogEntry
import com.studentlearning.platform2026.domain.model.BroadcastNotificationResult
import com.studentlearning.platform2026.domain.model.StaffMember
import com.studentlearning.platform2026.domain.repository.AdminGovernanceRepository
import com.studentlearning.platform2026.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal class AdminGovernanceRepositoryImpl(
    private val authRepository: AuthRepository,
    private val backendDataSource: BackendAdminVersionDataSource,
    private val dispatcherProvider: DispatcherProvider
) : AdminGovernanceRepository {

    override suspend fun getStaffMembers(): AppResult<List<StaffMember>> =
        executeOwnerAuthorized {
            backendDataSource.fetchStaffMembers()
        }

    override suspend fun setStaffAdminRoleByEmail(
        targetEmail: String,
        assignAdmin: Boolean
    ): AppResult<Unit> = executeOwnerAuthorized { session ->
        val cleanEmail = targetEmail.trim().lowercase()
        if (cleanEmail.isEmpty()) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.EMPTY_EMAIL,
                message = "Target email is required."
            )
        }
        if (!EMAIL_REGEX.matches(cleanEmail)) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.INVALID_EMAIL,
                message = "Target email format is invalid."
            )
        }
        if (session.email != null && session.email.trim().equals(cleanEmail, ignoreCase = true)) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.BACKEND_REQUEST_REJECTED,
                message = "Owner cannot modify their own staff role."
            )
        }
        backendDataSource.setStaffAdminRoleByEmail(
            targetEmail = cleanEmail,
            assignAdmin = assignAdmin
        )
    }

    override suspend fun getAuditLogs(limit: Int): AppResult<List<AuditLogEntry>> =
        executeAuthorized {
            backendDataSource.fetchAuditLogs(limit)
        }

    override suspend fun broadcastImportantUpdate(
        title: String,
        body: String
    ): AppResult<BroadcastNotificationResult> = executeAuthorized {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isEmpty() || cleanBody.isEmpty()) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.EMPTY_NAME,
                message = "Notification title and body must not be empty."
            )
        }
        if (cleanTitle.length > 120) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.NAME_TOO_LONG,
                message = "Notification title exceeds 120 characters."
            )
        }
        if (cleanBody.length > 500) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.DESCRIPTION_TOO_LONG,
                message = "Notification body exceeds 500 characters."
            )
        }
        backendDataSource.broadcastImportantUpdate(title = cleanTitle, body = cleanBody)
    }

    private suspend fun <T> executeOwnerAuthorized(
        block: suspend (session: AdminSession) -> T
    ): AppResult<T> = executeAuthorizedInternal(requireOwner = true, block = block)

    private suspend fun <T> executeAuthorized(
        block: suspend (session: AdminSession) -> T
    ): AppResult<T> = executeAuthorizedInternal(requireOwner = false, block = block)

    private suspend fun <T> executeAuthorizedInternal(
        requireOwner: Boolean,
        block: suspend (session: AdminSession) -> T
    ): AppResult<T> {
        return withContext(dispatcherProvider.io) {
            try {
                val session = authRepository.requireAuthorizedAdminSession()
                if (requireOwner && !session.canPerformOwnerOnlyActions) {
                    throw AdminException(
                        reason = AdminFailureReason.INSUFFICIENT_ROLE,
                        message = "This action is restricted to Owner accounts only."
                    )
                }
                val actorUid = session.uid?.trim().orEmpty()
                if (actorUid.isEmpty()) {
                    throw AdminException(
                        reason = AdminFailureReason.UNAUTHENTICATED,
                        message = "Authenticated admin UID is required."
                    )
                }
                AppResult.Success(block(session))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val adminException = when {
                    throwable is AdminException -> throwable
                    throwable.cause is AdminException -> throwable.cause as AdminException
                    else -> AdminException(
                        reason = FirestoreAdminCatalogMapper.mapThrowableToAdminReason(throwable),
                        message = throwable.localizedMessage,
                        cause = throwable
                    )
                }
                AppResult.Error(
                    message = adminException.message ?: "Admin governance operation failed",
                    cause = adminException
                )
            }
        }
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
