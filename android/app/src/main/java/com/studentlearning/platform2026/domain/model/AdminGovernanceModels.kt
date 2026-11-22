package com.studentlearning.platform2026.domain.model

data class StaffMember(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val role: AdminRole,
    val disabled: Boolean = false,
    val emailVerified: Boolean = false
)

data class AuditLogEntry(
    val id: String,
    val action: String,
    val actorUid: String,
    val actorRole: String,
    val targetCollection: String,
    val targetId: String,
    val summary: String,
    val source: String,
    val createdAtEpochMillis: Long?
)

data class BroadcastNotificationResult(
    val notificationId: String,
    val topic: String,
    val type: String,
    val title: String,
    val body: String,
    val sent: Boolean,
    val reason: String? = null
)
