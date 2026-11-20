package com.studentlearning.platform2026.data.pdf

import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import java.net.URLEncoder

interface PdfFileSourceDataSource {
    fun getBrokerStatus(): PdfBrokerConfigurationStatus

    suspend fun resolvePdfSource(
        fileId: String,
        activeVersionId: String?
    ): PdfSourceDescriptor
}

/**
 * Resolves a readable PDF source via a future secure Cloudflare R2 broker endpoint.
 *
 * Security & architecture guarantees:
 * - Contains NO Cloudflare R2 access keys, secrets, account IDs, or public bucket assumptions.
 * - Creates NO fake URLs or placeholder PDF files when unconfigured.
 * - Reports [PdfFailureReason.NO_PUBLISHED_VERSION] if the file metadata has no published version yet.
 * - Reports [PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED] until the secure signed-URL broker is attached.
 * - Transport policy: the broker must stream PDF bytes directly over HTTPS (`HTTP 200 OK`) or return a
 *   direct HTTPS target URL prior to fetching; 3xx redirects are intentionally disabled in the HTTP client.
 */
class BrokeredPdfFileSourceDataSource(
    private val brokerEndpointBaseUrl: String? = null
) : PdfFileSourceDataSource {

    override fun getBrokerStatus(): PdfBrokerConfigurationStatus {
        val base = brokerEndpointBaseUrl?.trim()
        return if (!base.isNullOrBlank() && base.startsWith("https://", ignoreCase = true)) {
            PdfBrokerConfigurationStatus.CONFIGURED
        } else {
            PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
        }
    }

    override suspend fun resolvePdfSource(
        fileId: String,
        activeVersionId: String?
    ): PdfSourceDescriptor {
        if (fileId.isBlank()) {
            throw PdfLoadException(
                reason = PdfFailureReason.FILE_NOT_FOUND,
                message = "Study file identifier is missing."
            )
        }
        if (activeVersionId.isNullOrBlank()) {
            throw PdfLoadException(
                reason = PdfFailureReason.NO_PUBLISHED_VERSION,
                message = "Study file does not have an active published PDF version yet."
            )
        }

        val normalizedBaseUrl = brokerEndpointBaseUrl?.trim()?.trimEnd('/')
        if (normalizedBaseUrl.isNullOrBlank() ||
            !normalizedBaseUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw PdfLoadException(
                reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
                message = "Secure media broker endpoint is not configured yet."
            )
        }

        val encodedFileId = encodePathSegment(fileId.trim())
        val encodedVersionId = encodePathSegment(activeVersionId.trim())
        return PdfSourceDescriptor.AuthorizedRemoteUrl(
            url = "$normalizedBaseUrl/study-files/$encodedFileId/versions/$encodedVersionId/stream",
            cacheKey = "${fileId.trim()}_${activeVersionId.trim()}"
        )
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
