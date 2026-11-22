package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AuditLogEntry
import com.studentlearning.platform2026.domain.model.BroadcastNotificationResult
import com.studentlearning.platform2026.domain.model.StaffMember

interface AdminGovernanceRepository {
    suspend fun getStaffMembers(): AppResult<List<StaffMember>>
    suspend fun setStaffAdminRoleByEmail(
        targetEmail: String,
        assignAdmin: Boolean
    ): AppResult<Unit>
    suspend fun getAuditLogs(limit: Int = 50): AppResult<List<AuditLogEntry>>
    suspend fun broadcastImportantUpdate(
        title: String,
        body: String
    ): AppResult<BroadcastNotificationResult>
}
