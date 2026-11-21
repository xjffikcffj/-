package com.studentlearning.platform2026.data.pdf

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.data.remote.backend.BackendJson
import com.studentlearning.platform2026.data.remote.backend.BackendVersionUploadClient
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import com.studentlearning.platform2026.domain.repository.PdfVersionStorageBroker
import com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 8 broker adapter for Cloudflare R2 versioned PDF uploads via Backend D2.
 *
 * - Contains ZERO Cloudflare R2 credentials, access keys, secrets, or fake upload URLs.
 * - Never treats a bare string in [brokerEndpointBaseUrl] as ready; requires BOTH a valid endpoint URL
 *   AND a real [backendUploadClient] before reporting [PdfBrokerConfigurationStatus.CONFIGURED].
 * - Fails closed when [backendUploadClient] is null or the backend endpoint URL is not configured.
 */
class BrokeredPdfVersionStorageDataSource(
    private val brokerEndpointBaseUrl: String? = null,
    private val backendUploadClient: BackendVersionUploadClient? = null,
    private val allowInsecureHttpForTesting: Boolean = false
) : PdfVersionStorageBroker {

    private val latestUploadSessions = ConcurrentHashMap<Pair<String, String>, String>()

    override fun getBrokerStatus(): PdfBrokerConfigurationStatus {
        val client = backendUploadClient ?: return PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
        if (!client.isEndpointConfigured) return PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
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
        val cleanSha = checksumSha256.trim().lowercase()
        if (cleanFileId.isEmpty() ||
            cleanVersionId.isEmpty() ||
            fileSizeBytes <= 0L ||
            !SHA256_HEX_REGEX.matches(cleanSha)
        ) {
            return AppResult.Error(
                message = "Invalid file or version upload parameters.",
                cause = AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.VERSION_UPLOAD_INVALID_PDF,
                    message = "Invalid file or version upload parameters."
                )
            )
        }

        val client = requireConfiguredClientOrNull()
            ?: return AppResult.Error(
                message = "Real backend signed upload slot broker is not integrated yet.",
                cause = PdfLoadException(
                    reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
                    message = "Real backend signed upload slot broker is not integrated yet."
                )
            )

        return try {
            val slot = client.createUploadSlot(
                fileId = cleanFileId,
                versionId = cleanVersionId,
                expectedSizeBytes = fileSizeBytes,
                expectedSha256 = cleanSha
            )
            val sessionId = slot.uploadSessionId?.trim()?.takeIf { it.isNotEmpty() }
            if (sessionId != null) {
                latestUploadSessions[cleanFileId to cleanVersionId] = sessionId
            }
            AppResult.Success(slot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            AppResult.Error(
                message = throwable.localizedMessage ?: "Failed creating upload slot.",
                cause = throwable
            )
        }
    }

    override suspend fun uploadPdfToSignedSlot(
        slot: PdfVersionUploadSlot,
        binarySource: PdfUploadBinarySource
    ): AppResult<Unit> {
        val client = requireConfiguredClientOrNull()
            ?: return AppResult.Error(
                message = "Real backend signed upload slot broker is not integrated yet.",
                cause = PdfLoadException(
                    reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
                    message = "Real backend signed upload slot broker is not integrated yet."
                )
            )
        return try {
            client.uploadPdfToSlot(slot = slot, binarySource = binarySource)
            AppResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            AppResult.Error(
                message = throwable.localizedMessage ?: "Failed uploading PDF binary to signed slot.",
                cause = throwable
            )
        }
    }

    override suspend fun verifyUploadedPdfBinary(
        fileId: String,
        versionId: String
    ): AppResult<VerifiedPdfBinaryProof> = verifyUploadedPdfBinary(
        fileId = fileId,
        versionId = versionId,
        uploadSessionId = null
    )

    override suspend fun verifyUploadedPdfBinary(
        fileId: String,
        versionId: String,
        uploadSessionId: String?
    ): AppResult<VerifiedPdfBinaryProof> {
        val cleanFileId = fileId.trim()
        val cleanVersionId = versionId.trim()
        val client = requireConfiguredClientOrNull()
            ?: return AppResult.Error(
                message = "Real backend R2 binary verification service is not integrated yet.",
                cause = PdfLoadException(
                    reason = PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED,
                    message = "Real backend R2 binary verification service is not integrated yet."
                )
            )

        return try {
            val resolvedSessionId = uploadSessionId?.trim()?.takeIf { it.isNotEmpty() }
                ?: latestUploadSessions[cleanFileId to cleanVersionId]
                ?: client.findLatestVerifiedSessionId(cleanFileId, cleanVersionId)
                ?: return AppResult.Error(
                    message = "No uploaded PDF session found for version '$cleanVersionId'.",
                    cause = AdminException(
                        reason = AdminFailureReason.VALIDATION_ERROR,
                        validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                        message = "Upload and verify a PDF file before publishing this version."
                    )
                )

            val proof = client.verifyUploadedPdf(
                fileId = cleanFileId,
                versionId = cleanVersionId,
                uploadSessionId = resolvedSessionId
            )
            latestUploadSessions[cleanFileId to cleanVersionId] = proof.uploadSessionId ?: resolvedSessionId
            AppResult.Success(proof)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            AppResult.Error(
                message = throwable.localizedMessage ?: "Server PDF binary verification failed.",
                cause = throwable
            )
        }
    }

    private fun requireConfiguredClientOrNull(): BackendVersionUploadClient? {
        if (getBrokerStatus() != PdfBrokerConfigurationStatus.CONFIGURED) return null
        return backendUploadClient
    }

    private fun sanitizePathComponent(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return URLEncoder.encode(trimmed, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private companion object {
        val SHA256_HEX_REGEX = Regex("^[a-f0-9]{64}$")
    }
}
