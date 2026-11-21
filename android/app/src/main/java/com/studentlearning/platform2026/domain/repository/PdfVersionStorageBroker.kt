package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult
import java.io.InputStream

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
data class PdfUploadBinarySource(
    val sizeBytes: Long,
    val sha256Hex: String,
    val displayName: String? = null,
    val openInputStream: () -> InputStream
)

data class PdfVersionUploadSlot(
    val fileId: String,
    val versionId: String,
    val r2ObjectKey: String,
    val signedUploadUrl: String,
    val uploadSessionId: String? = null,
    val requiredHeaders: Map<String, String> = emptyMap(),
    val expiresAtEpochMillis: Long? = null
)

data class VerifiedPdfBinaryProof(
    val fileId: String,
    val versionId: String,
    val r2ObjectKey: String,
    val fileSizeBytes: Long,
    val checksumSha256: String,
    val uploadSessionId: String? = null,
    val attemptId: String? = null
)

interface PdfVersionStorageBroker {
    fun getBrokerStatus(): PdfBrokerConfigurationStatus

    suspend fun requestVersionUploadSlot(
        fileId: String,
        versionId: String,
        fileSizeBytes: Long,
        checksumSha256: String
    ): AppResult<PdfVersionUploadSlot>

    suspend fun uploadPdfToSignedSlot(
        slot: PdfVersionUploadSlot,
        binarySource: PdfUploadBinarySource
    ): AppResult<Unit> = AppResult.Error(
        message = "Real backend signed upload slot broker is not integrated yet."
    )

    suspend fun verifyUploadedPdfBinary(
        fileId: String,
        versionId: String
    ): AppResult<VerifiedPdfBinaryProof>

    suspend fun verifyUploadedPdfBinary(
        fileId: String,
        versionId: String,
        uploadSessionId: String?
    ): AppResult<VerifiedPdfBinaryProof> = verifyUploadedPdfBinary(fileId, versionId)
}
