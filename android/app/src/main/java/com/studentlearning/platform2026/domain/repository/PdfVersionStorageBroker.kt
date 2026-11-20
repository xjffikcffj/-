package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult

/**
 * Architectural contract for future secure Cloudflare R2 version uploads.
 *
 * Security & storage rules:
 * - Each version gets an independent object key scoped strictly to `(fileId, versionId)`.
 * - NEVER duplicates binaries per student or embeds user UIDs inside storage keys.
 * - NEVER stores Cloudflare R2 access keys, account IDs, or fake signed URLs in the client.
 * - Remains explicitly unconfigured ([PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION])
 *   until a real backend signed-URL broker is attached.
 */
data class PdfVersionUploadSlot(
    val fileId: String,
    val versionId: String,
    val r2ObjectKey: String,
    val signedUploadUrl: String
)

interface PdfVersionStorageBroker {
    fun getBrokerStatus(): PdfBrokerConfigurationStatus

    fun buildVersionObjectKey(fileId: String, versionId: String): String

    suspend fun requestVersionUploadSlot(
        fileId: String,
        versionId: String,
        fileSizeBytes: Long,
        checksumSha256: String
    ): AppResult<PdfVersionUploadSlot>
}
