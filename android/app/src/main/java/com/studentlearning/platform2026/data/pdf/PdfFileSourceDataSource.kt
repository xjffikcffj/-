package com.studentlearning.platform2026.data.pdf

import com.studentlearning.platform2026.data.remote.backend.BackendJson
import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import kotlinx.coroutines.CancellationException

interface PdfFileSourceDataSource {
    fun getBrokerStatus(): PdfBrokerConfigurationStatus

    suspend fun resolvePdfSource(
        fileId: String,
        activeVersionId: String?
    ): PdfSourceDescriptor

    suspend fun resolveDownloadPdfSource(
        fileId: String,
        activeVersionId: String?
    ): PdfSourceDescriptor = resolvePdfSource(fileId, activeVersionId)
}

/**
 * Resolves readable and downloadable PDF sources via the Stage 8 Backend D2 broker endpoint.
 *
 * Security & architecture guarantees:
 * - Contains NO Cloudflare R2 access keys, secrets, account IDs, or direct bucket URLs.
 * - Creates NO fake URLs or placeholder PDF files when unconfigured.
 * - Stream endpoint (`/api/study-files/{fileId}/versions/{versionId}/stream`) works for guests and signed-in users
 *   (attaching Bearer token opportunistically if signed in).
 * - Download endpoint (`/api/study-files/{fileId}/versions/{versionId}/download`) requires a verified non-guest
 *   Firebase ID token via `Authorization: Bearer <token>`.
 * - Reports [PdfFailureReason.NO_PUBLISHED_VERSION] if the file metadata has no published version yet.
 * - Reports [PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED] until a valid HTTPS backend base URL is configured.
 * - Transport policy: broker streams PDF bytes directly over HTTPS (`HTTP 200 OK`); 3xx redirects remain disabled.
 */
class BrokeredPdfFileSourceDataSource(
    private val brokerEndpointBaseUrl: String? = null,
    private val authTokenProvider: AuthTokenProvider? = null,
    private val allowInsecureHttpForTesting: Boolean = false
) : PdfFileSourceDataSource {

    override fun getBrokerStatus(): PdfBrokerConfigurationStatus {
        val apiRoot = BackendJson.normalizeApiRootUrl(
            rawBaseUrl = brokerEndpointBaseUrl,
            allowInsecureHttpForTesting = allowInsecureHttpForTesting
        )
        return if (apiRoot != null) {
            PdfBrokerConfigurationStatus.CONFIGURED
        } else {
            PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
        }
    }

    override suspend fun resolvePdfSource(
        fileId: String,
        activeVersionId: String?
    ): PdfSourceDescriptor {
        val (apiRoot, cleanFileId, cleanVersionId) = validateAndResolveCoordinates(fileId, activeVersionId)
        val optionalToken = try {
            authTokenProvider?.getIdTokenOrNull(forceRefresh = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val headers = if (!optionalToken.isNullOrBlank()) {
            mapOf("Authorization" to "Bearer $optionalToken")
        } else {
            emptyMap()
        }
        val encodedFileId = BackendJson.encodePathSegment(cleanFileId)
        val encodedVersionId = BackendJson.encodePathSegment(cleanVersionId)
        return PdfSourceDescriptor.AuthorizedRemoteUrl(
            url = "$apiRoot/study-files/$encodedFileId/versions/$encodedVersionId/stream",
            cacheKey = "${cleanFileId}_${cleanVersionId}",
            headers = headers
        )
    }

    override suspend fun resolveDownloadPdfSource(
        fileId: String,
        activeVersionId: String?
    ): PdfSourceDescriptor {
        val (apiRoot, cleanFileId, cleanVersionId) = validateAndResolveCoordinates(fileId, activeVersionId)
        val bearerToken = try {
            authTokenProvider?.getIdTokenOrNull(forceRefresh = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            throw PdfLoadException(
                reason = PdfFailureReason.UNAUTHORIZED_ACCESS,
                message = throwable.localizedMessage ?: "Authentication required to download study PDFs.",
                cause = throwable
            )
        }
        if (bearerToken.isNullOrBlank()) {
            throw PdfLoadException(
                reason = PdfFailureReason.UNAUTHORIZED_ACCESS,
                message = "A signed-in non-guest account token is required for offline PDF downloads."
            )
        }

        val encodedFileId = BackendJson.encodePathSegment(cleanFileId)
        val encodedVersionId = BackendJson.encodePathSegment(cleanVersionId)
        return PdfSourceDescriptor.AuthorizedRemoteUrl(
            url = "$apiRoot/study-files/$encodedFileId/versions/$encodedVersionId/download",
            cacheKey = "${cleanFileId}_${cleanVersionId}",
            headers = mapOf("Authorization" to "Bearer $bearerToken")
        )
    }

    private fun validateAndResolveCoordinates(
        fileId: String,
        activeVersionId: String?
    ): Triple<String, String, String> {
        val cleanFileId = fileId.trim()
        if (cleanFileId.isEmpty()) {
            throw PdfLoadException(
                reason = PdfFailureReason.FILE_NOT_FOUND,
                message = "Study file identifier is missing."
            )
        }
        val cleanVersionId = activeVersionId?.trim()
        if (cleanVersionId.isNullOrEmpty()) {
            throw PdfLoadException(
                reason = PdfFailureReason.NO_PUBLISHED_VERSION,
                message = "Study file does not have an active published PDF version yet."
            )
        }
        val apiRoot = BackendJson.normalizeApiRootUrl(
            rawBaseUrl = brokerEndpointBaseUrl,
            allowInsecureHttpForTesting = allowInsecureHttpForTesting
        ) ?: throw PdfLoadException(
            reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
            message = "Secure media broker endpoint is not configured yet."
        )
        return Triple(apiRoot, cleanFileId, cleanVersionId)
    }
}
