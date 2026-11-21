package com.studentlearning.platform2026.data.remote.backend

import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import kotlinx.coroutines.CancellationException
import java.io.IOException

interface BackendVersionUploadClient {
    val isEndpointConfigured: Boolean

    suspend fun createUploadSlot(
        fileId: String,
        versionId: String,
        expectedSizeBytes: Long,
        expectedSha256: String
    ): PdfVersionUploadSlot

    suspend fun uploadPdfToSlot(
        slot: PdfVersionUploadSlot,
        binarySource: PdfUploadBinarySource
    )

    suspend fun verifyUploadedPdf(
        fileId: String,
        versionId: String,
        uploadSessionId: String
    ): VerifiedPdfBinaryProof

    suspend fun findLatestVerifiedSessionId(
        fileId: String,
        versionId: String
    ): String?
}

internal class BackendAdminVersionDataSource(
    private val httpClient: BackendHttpClient,
    private val authTokenProvider: AuthTokenProvider
) : BackendVersionUploadClient {

    override val isEndpointConfigured: Boolean
        get() = httpClient.isConfigured

    suspend fun fetchStudyFileVersions(fileId: String): List<StudyFileVersionMetadata> {
        val cleanFileId = fileId.trim()
        if (cleanFileId.isEmpty()) return emptyList()
        val encodedFileId = BackendJson.encodePathSegment(cleanFileId)
        val root = executeAdminBackendCall { token ->
            httpClient.getJsonObject("admin/study-files/$encodedFileId/versions", bearerToken = token)
        }
        return root.objectListOrEmpty("items")
            .mapNotNull { item -> mapAdminVersionDto(item, fallbackFileId = cleanFileId) }
            .sortedWith(
                compareByDescending<StudyFileVersionMetadata> { it.versionNumber }
                    .thenByDescending { it.publishedAtEpochMillis ?: 0L }
                    .thenByDescending { it.createdAtEpochMillis ?: 0L }
            )
    }

    suspend fun createStudyFileVersion(
        fileId: String,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        customVersionId: String?
    ): StudyFileVersionMetadata {
        val cleanFileId = fileId.trim()
        val encodedFileId = BackendJson.encodePathSegment(cleanFileId)
        val payload = linkedMapOf<String, Any?>(
            "versionNumber" to versionNumber,
            "versionLabel" to versionLabel
        )
        if (!versionNotes.isNullOrBlank()) {
            payload["versionNotes"] = versionNotes
        }
        if (!customVersionId.isNullOrBlank()) {
            payload["customVersionId"] = customVersionId.trim()
        }
        val root = executeAdminBackendCall { token ->
            httpClient.postJsonObject(
                relativeApiPath = "admin/study-files/$encodedFileId/versions",
                body = payload,
                bearerToken = token
            )
        }
        val versionMap = root.objectOrNull("version")
            ?: throw AdminException(
                reason = AdminFailureReason.UNKNOWN,
                message = "Backend did not return created version metadata."
            )
        return mapAdminVersionDto(versionMap, fallbackFileId = cleanFileId)
            ?: throw AdminException(
                reason = AdminFailureReason.UNKNOWN,
                message = "Backend returned invalid version metadata."
            )
    }

    suspend fun updateStudyFileVersionMetadata(
        fileId: String,
        versionId: String,
        versionLabel: String,
        versionNotes: String?
    ) {
        val encodedFileId = BackendJson.encodePathSegment(fileId)
        val encodedVersionId = BackendJson.encodePathSegment(versionId)
        val payload = linkedMapOf<String, Any?>(
            "versionLabel" to versionLabel,
            "versionNotes" to versionNotes?.takeIf { it.isNotBlank() }
        )
        executeAdminBackendCall { token ->
            httpClient.patchJsonObject(
                relativeApiPath = "admin/study-files/$encodedFileId/versions/$encodedVersionId",
                body = payload,
                bearerToken = token
            )
        }
    }

    suspend fun publishStudyFileVersion(
        fileId: String,
        versionId: String
    ) {
        val encodedFileId = BackendJson.encodePathSegment(fileId)
        val encodedVersionId = BackendJson.encodePathSegment(versionId)
        executeAdminBackendCall { token ->
            httpClient.postJsonObject(
                relativeApiPath = "admin/study-files/$encodedFileId/versions/$encodedVersionId/publish",
                body = emptyMap(),
                bearerToken = token
            )
        }
    }

    override suspend fun createUploadSlot(
        fileId: String,
        versionId: String,
        expectedSizeBytes: Long,
        expectedSha256: String
    ): PdfVersionUploadSlot {
        val cleanFileId = fileId.trim()
        val cleanVersionId = versionId.trim()
        val encodedFileId = BackendJson.encodePathSegment(cleanFileId)
        val encodedVersionId = BackendJson.encodePathSegment(cleanVersionId)
        val root = executeAdminBackendCall { token ->
            httpClient.postJsonObject(
                relativeApiPath = "admin/study-files/$encodedFileId/versions/$encodedVersionId/upload-slot",
                body = mapOf(
                    "expectedSizeBytes" to expectedSizeBytes,
                    "expectedSha256" to expectedSha256.trim().lowercase()
                ),
                bearerToken = token
            )
        }
        val sessionId = root.stringOrNull("uploadSessionId")
            ?: throw AdminException(AdminFailureReason.UNKNOWN, message = "Missing uploadSessionId in upload slot response.")
        val uploadUrl = root.stringOrNull("uploadUrl")
            ?: throw AdminException(AdminFailureReason.UNKNOWN, message = "Missing uploadUrl in upload slot response.")
        val stagingKey = root.stringOrNull("stagingKey").orEmpty()
        val headersMap = root.objectOrNull("requiredHeaders")
            ?.entries
            ?.mapNotNull { (k, v) ->
                val strVal = v?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                k to strVal
            }
            ?.toMap()
            ?: emptyMap()
        return PdfVersionUploadSlot(
            fileId = root.stringOrNull("fileId") ?: cleanFileId,
            versionId = root.stringOrNull("versionId") ?: cleanVersionId,
            r2ObjectKey = stagingKey,
            signedUploadUrl = uploadUrl,
            uploadSessionId = sessionId,
            requiredHeaders = headersMap,
            expiresAtEpochMillis = root.longOrNull("expiresAtEpochMillis")
        )
    }

    override suspend fun uploadPdfToSlot(
        slot: PdfVersionUploadSlot,
        binarySource: PdfUploadBinarySource
    ) {
        try {
            httpClient.uploadPdfStreamPut(
                uploadUrl = slot.signedUploadUrl,
                requiredHeaders = slot.requiredHeaders,
                binarySource = binarySource
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notConfigured: BackendNotConfiguredException) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = notConfigured.message,
                cause = notConfigured
            )
        } catch (httpError: BackendHttpException) {
            throw mapR2PutErrorToAdminException(httpError)
        } catch (io: IOException) {
            throw AdminException(
                reason = AdminFailureReason.NETWORK_UNAVAILABLE,
                message = "Network error uploading PDF binary to temporary storage.",
                cause = sanitizeUploadIoCause(io)
            )
        }
    }

    override suspend fun verifyUploadedPdf(
        fileId: String,
        versionId: String,
        uploadSessionId: String
    ): VerifiedPdfBinaryProof {
        val cleanFileId = fileId.trim()
        val cleanVersionId = versionId.trim()
        val encodedFileId = BackendJson.encodePathSegment(cleanFileId)
        val encodedVersionId = BackendJson.encodePathSegment(cleanVersionId)
        val root = executeAdminBackendCall { token ->
            httpClient.postJsonObject(
                relativeApiPath = "admin/study-files/$encodedFileId/versions/$encodedVersionId/verify",
                body = mapOf("uploadSessionId" to uploadSessionId.trim()),
                bearerToken = token
            )
        }
        val r2ObjectKey = root.stringOrNull("r2ObjectKey")
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Verification response missing r2ObjectKey."
            )
        val sizeBytes = root.longOrNull("fileSizeBytes")
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Verification response missing fileSizeBytes."
            )
        val sha256 = root.stringOrNull("checksumSha256")?.lowercase()
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Verification response missing checksumSha256."
            )
        return VerifiedPdfBinaryProof(
            fileId = root.stringOrNull("fileId") ?: cleanFileId,
            versionId = root.stringOrNull("versionId") ?: cleanVersionId,
            r2ObjectKey = r2ObjectKey,
            fileSizeBytes = sizeBytes,
            checksumSha256 = sha256,
            uploadSessionId = root.stringOrNull("uploadSessionId") ?: uploadSessionId.trim(),
            attemptId = root.stringOrNull("attemptId")
        )
    }

    override suspend fun findLatestVerifiedSessionId(
        fileId: String,
        versionId: String
    ): String? {
        val versions = fetchStudyFileVersions(fileId)
        val match = versions.firstOrNull { it.id == versionId.trim() } ?: return null
        return match.verifiedUploadSessionId?.takeIf {
            match.binaryVerificationStatus == "VERIFIED"
        }
    }

    private suspend fun executeAdminBackendCall(
        call: suspend (token: String) -> Map<String, Any?>
    ): Map<String, Any?> {
        if (!httpClient.isConfigured) {
            throw AdminException(
                reason = AdminFailureReason.FIREBASE_NOT_CONFIGURED,
                message = "Backend API endpoint URL is not configured yet."
            )
        }
        val token = authTokenProvider.getIdTokenOrNull(forceRefresh = true)
            ?: throw AdminException(
                reason = AdminFailureReason.UNAUTHENTICATED,
                message = "Authenticated staff ID token is required."
            )
        return try {
            call(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (existing: AdminException) {
            throw existing
        } catch (notConfigured: BackendNotConfiguredException) {
            throw AdminException(
                reason = AdminFailureReason.FIREBASE_NOT_CONFIGURED,
                message = notConfigured.message,
                cause = notConfigured
            )
        } catch (httpError: BackendHttpException) {
            throw mapHttpErrorToAdminException(httpError)
        } catch (io: IOException) {
            throw AdminException(
                reason = AdminFailureReason.NETWORK_UNAVAILABLE,
                message = io.localizedMessage ?: "Network error contacting admin backend.",
                cause = io
            )
        } catch (throwable: Throwable) {
            throw AdminException(
                reason = AdminFailureReason.UNKNOWN,
                message = throwable.localizedMessage ?: "Unexpected error contacting admin backend.",
                cause = throwable
            )
        }
    }

    private fun mapAdminVersionDto(
        item: Map<String, Any?>,
        fallbackFileId: String
    ): StudyFileVersionMetadata? {
        val id = item.stringOrNull("id") ?: return null
        val fileId = item.stringOrNull("fileId") ?: fallbackFileId
        val versionNumber = item.intOrNull("versionNumber")?.takeIf { it >= 1 } ?: return null
        val versionLabel = item.stringOrNull("versionLabel") ?: return null
        val verificationStatus = item.stringOrNull("binaryVerificationStatus")
        val fileSizeBytes = item.longOrNull("fileSizeBytes")?.takeIf { it > 0L }
        val checksumSha256 = item.stringOrNull("checksumSha256")?.lowercase()
        val verifiedUploadSessionId = item.stringOrNull("verifiedUploadSessionId")
        val verifiedAttemptId = item.stringOrNull("verifiedAttemptId")
        // F-AND-2: Preserve only server-supplied r2ObjectKey if explicitly present;
        // never synthesize artificial storage paths when Backend D2 omits internal keys.
        val r2ObjectKey = item.stringOrNull("r2ObjectKey")

        return StudyFileVersionMetadata(
            id = id,
            fileId = fileId,
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            versionNotes = item.stringOrNull("versionNotes"),
            r2ObjectKey = r2ObjectKey,
            fileSizeBytes = fileSizeBytes,
            checksumSha256 = checksumSha256,
            isPublished = item.booleanOrFalse("isPublished"),
            publishedAtEpochMillis = item.longOrNull("publishedAtEpochMillis"),
            createdAtEpochMillis = item.longOrNull("createdAtEpochMillis"),
            fileReferencePath = "study_files/$fileId",
            updatedAtEpochMillis = item.longOrNull("updatedAtEpochMillis"),
            isArchived = item.booleanOrFalse("isArchived"),
            binaryVerificationStatus = verificationStatus,
            verifiedUploadSessionId = verifiedUploadSessionId,
            verifiedAttemptId = verifiedAttemptId
        )
    }

    private fun sanitizeUploadIoCause(io: IOException): IOException {
        val safeType = io::class.java.simpleName.takeIf { it.isNotBlank() } ?: "IOException"
        return IOException("Sanitized $safeType during R2 upload.").apply {
            stackTrace = io.stackTrace
        }
    }

    private fun mapR2PutErrorToAdminException(httpError: BackendHttpException): AdminException {
        val code = httpError.errorCode
        return when {
            httpError.isR2UrlExpired ||
                httpError.statusCode == 410 ||
                code.equals("ExpiredToken", ignoreCase = true) ||
                code.equals("TokenRefreshRequired", ignoreCase = true) -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_SESSION_EXPIRED,
                message = httpError.message,
                cause = httpError
            )
            httpError.statusCode == 413 ||
                code.equals("EntityTooLarge", ignoreCase = true) ||
                code.equals("MaxSizeExceeded", ignoreCase = true) -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_INVALID_PDF,
                message = httpError.message,
                cause = httpError
            )
            httpError.statusCode in 500..599 -> AdminException(
                reason = AdminFailureReason.NETWORK_UNAVAILABLE,
                message = httpError.message,
                cause = httpError
            )
            else -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_R2_REJECTED,
                message = httpError.message,
                cause = httpError
            )
        }
    }

    private fun mapHttpErrorToAdminException(httpError: BackendHttpException): AdminException {
        if (httpError.isR2StorageError) {
            return mapR2PutErrorToAdminException(httpError)
        }
        val code = httpError.errorCode
        return when {
            httpError.statusCode == 401 -> AdminException(
                reason = AdminFailureReason.UNAUTHENTICATED,
                message = httpError.message,
                cause = httpError
            )
            httpError.statusCode == 403 -> AdminException(
                reason = if (code == "INSUFFICIENT_ROLE") {
                    AdminFailureReason.INSUFFICIENT_ROLE
                } else {
                    AdminFailureReason.PERMISSION_DENIED
                },
                message = httpError.message,
                cause = httpError
            )
            code == "R2_OBJECT_MISSING" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = httpError.message,
                cause = httpError
            )
            httpError.statusCode == 404 -> AdminException(
                reason = AdminFailureReason.NOT_FOUND,
                message = httpError.message,
                cause = httpError
            )
            code == "R2_NOT_CONFIGURED" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.R2_STORAGE_NOT_CONFIGURED,
                message = httpError.message,
                cause = httpError
            )
            code == "VERSION_NUMBER_CONFLICT" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.INVALID_VERSION_NUMBER,
                message = httpError.message,
                cause = httpError
            )
            code == "FILE_ARCHIVED" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                message = httpError.message,
                cause = httpError
            )
            code == "VERSION_ARCHIVED" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_ARCHIVED_CANNOT_PUBLISH,
                message = httpError.message,
                cause = httpError
            )
            code == "VERSION_ALREADY_PUBLISHED" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_ALREADY_PUBLISHED_IMMUTABLE,
                message = httpError.message,
                cause = httpError
            )
            code == "VERSION_ALREADY_VERIFIED" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_ALREADY_VERIFIED_IMMUTABLE,
                message = httpError.message,
                cause = httpError
            )
            code == "INVALID_PDF_HEADER" ||
                code == "INVALID_PDF_MAGIC" ||
                code == "PDF_TOO_LARGE" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_INVALID_PDF,
                message = httpError.message,
                cause = httpError
            )
            code == "UPLOAD_SIZE_MISMATCH" ||
                (code == "SIZE_MISMATCH" && httpError.statusCode == 422) -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_SIZE_MISMATCH,
                message = httpError.message,
                cause = httpError
            )
            code == "SHA256_MISMATCH" ||
                code == "UPLOAD_CHECKSUM_MISMATCH" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_SHA256_MISMATCH,
                message = httpError.message,
                cause = httpError
            )
            code == "ETAG_MISMATCH" ||
                httpError.statusCode == 412 -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_ETAG_MISMATCH,
                message = httpError.message,
                cause = httpError
            )
            code == "SESSION_EXPIRED" ||
                httpError.statusCode == 410 -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_SESSION_EXPIRED,
                message = httpError.message,
                cause = httpError
            )
            code == "SESSION_SUPERSEDED" ||
                code == "SESSION_FAILED" ||
                code == "SESSION_MISMATCH" ||
                code == "VERIFY_IN_PROGRESS" ||
                code == "CONFLICT_STALE_LOCK" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_SESSION_CONFLICT,
                message = httpError.message,
                cause = httpError
            )
            code == "PROOF_MISSING" ||
                code == "PROOF_MISMATCH" ||
                code == "SIZE_MISMATCH" -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = httpError.message,
                cause = httpError
            )
            httpError.statusCode == 502 ||
                httpError.statusCode == 503 -> AdminException(
                reason = AdminFailureReason.NETWORK_UNAVAILABLE,
                message = httpError.message,
                cause = httpError
            )
            httpError.statusCode == 400 ||
                httpError.statusCode == 409 ||
                httpError.statusCode == 422 -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.BACKEND_REQUEST_REJECTED,
                message = httpError.message,
                cause = httpError
            )
            else -> AdminException(
                reason = AdminFailureReason.UNKNOWN,
                message = httpError.message,
                cause = httpError
            )
        }
    }
}

/**
 * Hybrid Stage 8 data source:
 * - Catalog metadata (`branches`, `subjects`, `sections`, `study_files` metadata + archive/unpublish + reorder)
 *   remains on direct Firestore via [firestoreDataSource] under D1 `firestore.rules`.
 * - Study file version lifecycle (`fetchStudyFileVersions`, `createStudyFileVersion`,
 *   `updateStudyFileVersionMetadata`, `publishStudyFileVersion`) AND re-publishing a restored
 *   parent study file (`setStudyFilePublished(..., true)`) are strictly routed through
 *   [backendVersionDataSource] because `firestore.rules` reserves `versions` writes and
 *   `study_files.isPublished = true` exclusively for Backend D2.
 */
internal class HybridAdminCatalogDataSource(
    private val firestoreDataSource: AdminCatalogRemoteDataSource,
    private val backendVersionDataSource: BackendAdminVersionDataSource
) : AdminCatalogRemoteDataSource {

    override suspend fun fetchAllBranches(): List<StudyBranch> =
        firestoreDataSource.fetchAllBranches()

    override suspend fun createBranch(
        customId: String?,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudyBranch = firestoreDataSource.createBranch(customId, name, order, isPublished, actorUid)

    override suspend fun updateBranch(branchId: String, name: String, order: Int, actorUid: String) =
        firestoreDataSource.updateBranch(branchId, name, order, actorUid)

    override suspend fun setBranchPublished(branchId: String, isPublished: Boolean, actorUid: String) =
        firestoreDataSource.setBranchPublished(branchId, isPublished, actorUid)

    override suspend fun fetchAllSubjects(branchId: String): List<StudySubject> =
        firestoreDataSource.fetchAllSubjects(branchId)

    override suspend fun createSubject(
        customId: String?,
        branchId: String,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudySubject = firestoreDataSource.createSubject(customId, branchId, name, order, isPublished, actorUid)

    override suspend fun updateSubject(subjectId: String, name: String, order: Int, actorUid: String) =
        firestoreDataSource.updateSubject(subjectId, name, order, actorUid)

    override suspend fun setSubjectPublished(subjectId: String, isPublished: Boolean, actorUid: String) =
        firestoreDataSource.setSubjectPublished(subjectId, isPublished, actorUid)

    override suspend fun fetchAllSections(subjectId: String): List<StudySection> =
        firestoreDataSource.fetchAllSections(subjectId)

    override suspend fun createSection(
        customId: String?,
        subjectId: String,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudySection = firestoreDataSource.createSection(customId, subjectId, name, order, isPublished, actorUid)

    override suspend fun updateSection(sectionId: String, name: String, order: Int, actorUid: String) =
        firestoreDataSource.updateSection(sectionId, name, order, actorUid)

    override suspend fun setSectionPublished(sectionId: String, isPublished: Boolean, actorUid: String) =
        firestoreDataSource.setSectionPublished(sectionId, isPublished, actorUid)

    override suspend fun fetchStudyFilesForSection(sectionId: String): List<StudyFileMetadata> =
        firestoreDataSource.fetchStudyFilesForSection(sectionId)

    override suspend fun fetchStudyFileMetadata(fileId: String): StudyFileMetadata? =
        firestoreDataSource.fetchStudyFileMetadata(fileId)

    override suspend fun createStudyFileMetadata(
        customId: String?,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudyFileMetadata = firestoreDataSource.createStudyFileMetadata(
        customId = customId,
        sectionId = sectionId,
        title = title,
        description = description,
        order = order,
        isPublished = isPublished,
        actorUid = actorUid
    )

    override suspend fun updateStudyFileMetadata(
        fileId: String,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        actorUid: String
    ) = firestoreDataSource.updateStudyFileMetadata(fileId, sectionId, title, description, order, actorUid)

    override suspend fun setStudyFilePublished(fileId: String, isPublished: Boolean, actorUid: String) {
        if (!isPublished) {
            firestoreDataSource.setStudyFilePublished(fileId, isPublished = false, actorUid = actorUid)
            return
        }
        // D1 firestore.rules restricts setting study_files.isPublished = true exclusively to the trusted
        // Backend D2 publish endpoint. F-AND-3: Strictly respect the existing activeVersionId/activeVersionNumber
        // on study_files and NEVER auto-select or publish another draft version implicitly.
        val parentFile = firestoreDataSource.fetchStudyFileMetadata(fileId)
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                message = "Cannot publish study file '$fileId' without an active verified PDF version."
            )
        if (parentFile.isArchived) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                message = "Cannot publish archived study file '$fileId'."
            )
        }
        val activeVersionId = parentFile.activeVersionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                message = "Cannot publish study file '$fileId' because it has no activeVersionId."
            )
        val activeVersionNumber = parentFile.activeVersionNumber?.takeIf { it >= 1 }
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                message = "Cannot publish study file '$fileId' without a valid activeVersionNumber."
            )
        val versions = backendVersionDataSource.fetchStudyFileVersions(fileId)
        val activeVersion = versions.firstOrNull { it.id == activeVersionId }
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                message = "Active version '$activeVersionId' for study file '$fileId' was not found."
            )
        if (activeVersion.isArchived) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_ARCHIVED_CANNOT_PUBLISH,
                message = "Cannot publish study file '$fileId' while active version '$activeVersionId' is archived."
            )
        }
        if (activeVersion.versionNumber != activeVersionNumber || !activeVersion.hasUploadedPdfBinary) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Active version '$activeVersionId' does not have a valid server-verified PDF binary proof."
            )
        }
        backendVersionDataSource.publishStudyFileVersion(
            fileId = fileId,
            versionId = activeVersion.id
        )
    }

    override suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean, actorUid: String) =
        firestoreDataSource.setStudyFileArchived(fileId, isArchived, actorUid)

    override suspend fun fetchStudyFileVersions(fileId: String): List<StudyFileVersionMetadata> =
        backendVersionDataSource.fetchStudyFileVersions(fileId)

    override suspend fun createStudyFileVersion(
        fileId: String,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishAsActive: Boolean,
        actorUid: String,
        customVersionId: String?
    ): StudyFileVersionMetadata {
        if (publishAsActive) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Cannot publish an active version before its PDF binary is uploaded and verified."
            )
        }
        return backendVersionDataSource.createStudyFileVersion(
            fileId = fileId,
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            versionNotes = versionNotes,
            customVersionId = customVersionId
        )
    }

    override suspend fun updateStudyFileVersionMetadata(
        fileId: String,
        versionId: String,
        versionLabel: String,
        versionNotes: String?,
        actorUid: String
    ) = backendVersionDataSource.updateStudyFileVersionMetadata(
        fileId = fileId,
        versionId = versionId,
        versionLabel = versionLabel,
        versionNotes = versionNotes
    )

    override suspend fun publishStudyFileVersion(
        fileId: String,
        versionId: String,
        actorUid: String,
        verifiedProof: VerifiedPdfBinaryProof
    ) = backendVersionDataSource.publishStudyFileVersion(
        fileId = fileId,
        versionId = versionId
    )

    override suspend fun reorderBranches(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) = firestoreDataSource.reorderBranches(firstId, firstNewOrder, secondId, secondNewOrder, actorUid)

    override suspend fun reorderSubjects(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) = firestoreDataSource.reorderSubjects(firstId, firstNewOrder, secondId, secondNewOrder, actorUid)

    override suspend fun reorderSections(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) = firestoreDataSource.reorderSections(firstId, firstNewOrder, secondId, secondNewOrder, actorUid)

    override suspend fun reorderStudyFiles(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) = firestoreDataSource.reorderStudyFiles(firstId, firstNewOrder, secondId, secondNewOrder, actorUid)
}
