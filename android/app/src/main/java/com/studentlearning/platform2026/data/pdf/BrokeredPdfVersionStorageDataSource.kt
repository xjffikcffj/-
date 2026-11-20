package com.studentlearning.platform2026.data.pdf

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfVersionStorageBroker
import com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot
import java.net.URLEncoder

/**
 * Unconfigured broker adapter for future Cloudflare R2 versioned PDF uploads.
 *
 * - Builds deterministic, collision-free per-version storage keys (`study-files/{fileId}/versions/{versionId}/document.pdf`)
 *   without ever creating per-student cloud copies or embedding Firebase UIDs.
 * - Contains ZERO Cloudflare R2 credentials, access keys, secrets, or fake upload URLs.
 * - Reports [PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION] until the secure
 *   backend signed-URL broker endpoint is configured.
 */
class BrokeredPdfVersionStorageDataSource(
    private val brokerEndpointBaseUrl: String? = null
) : PdfVersionStorageBroker {

    override fun getBrokerStatus(): PdfBrokerConfigurationStatus {
        val base = brokerEndpointBaseUrl?.trim()
        return if (!base.isNullOrBlank() && base.startsWith("https://", ignoreCase = true)) {
            PdfBrokerConfigurationStatus.CONFIGURED
        } else {
            PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
        }
    }

    override fun buildVersionObjectKey(fileId: String, versionId: String): String {
        val safeFileId = sanitizePathComponent(fileId)
        val safeVersionId = sanitizePathComponent(versionId)
        require(safeFileId.isNotEmpty()) { "fileId must not be blank" }
        require(safeVersionId.isNotEmpty()) { "versionId must not be blank" }
        return "study-files/$safeFileId/versions/$safeVersionId/document.pdf"
    }

    override suspend fun requestVersionUploadSlot(
        fileId: String,
        versionId: String,
        fileSizeBytes: Long,
        checksumSha256: String
    ): AppResult<PdfVersionUploadSlot> {
        val cleanFileId = fileId.trim()
        val cleanVersionId = versionId.trim()
        if (cleanFileId.isEmpty() || cleanVersionId.isEmpty() || fileSizeBytes <= 0L || checksumSha256.isBlank()) {
            return AppResult.Error(
                message = "Invalid file or version upload parameters.",
                cause = PdfLoadException(
                    reason = PdfFailureReason.UNKNOWN,
                    message = "Invalid file or version upload parameters."
                )
            )
        }

        if (getBrokerStatus() != PdfBrokerConfigurationStatus.CONFIGURED) {
            return AppResult.Error(
                message = "Secure Cloudflare R2 upload broker is not configured yet.",
                cause = PdfLoadException(
                    reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
                    message = "Secure Cloudflare R2 upload broker is not configured yet."
                )
            )
        }

        return AppResult.Error(
            message = "Signed upload slot negotiation requires backend broker integration.",
            cause = PdfLoadException(
                reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
                message = "Signed upload slot negotiation requires backend broker integration."
            )
        )
    }

    private fun sanitizePathComponent(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return URLEncoder.encode(trimmed, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
