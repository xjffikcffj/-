package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Collections
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Defaults
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Fields
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import java.io.IOException
import java.net.URLEncoder

object FirestoreAdminCatalogMapper {

    private val SHA256_HEX_REGEX = Regex("^[a-fA-F0-9]{64}$")

    fun mapBranchForAdmin(id: String, data: Map<String, Any?>): StudyBranch? {
        if (id.isBlank()) return null

        val name = extractText(data, Fields.NAME_AR, Fields.NAME, Fields.TITLE) ?: return null
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE
        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false

        return StudyBranch(
            id = id,
            name = name,
            order = order,
            isPublished = isPublished,
            referencePath = "${Collections.BRANCHES}/$id"
        )
    }

    fun mapBranchesForAdmin(documents: List<Pair<String, Map<String, Any?>>>): List<StudyBranch> {
        return documents
            .mapNotNull { (id, data) -> mapBranchForAdmin(id, data) }
            .sortedWith(compareBy<StudyBranch> { it.order }.thenBy { it.name })
    }

    fun mapSubjectForAdmin(
        id: String,
        data: Map<String, Any?>,
        expectedBranchId: String
    ): StudySubject? {
        if (id.isBlank() || expectedBranchId.isBlank()) return null

        // Confirmed schema: subjects.branchId is a DocumentReference to branches/{branchId}.
        val branchRef = FirestoreCatalogMapper.extractReferenceTarget(data[Fields.BRANCH_ID]) ?: return null
        if (branchRef.collectionId != Collections.BRANCHES || branchRef.documentId != expectedBranchId) {
            return null
        }

        val name = extractText(data, Fields.NAME_AR, Fields.NAME, Fields.TITLE) ?: return null
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE
        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false

        return StudySubject(
            id = id,
            branchId = branchRef.documentId,
            name = name,
            order = order,
            isPublished = isPublished,
            branchReferencePath = branchRef.path
        )
    }

    fun mapSubjectsForAdmin(
        documents: List<Pair<String, Map<String, Any?>>>,
        expectedBranchId: String
    ): List<StudySubject> {
        return documents
            .mapNotNull { (id, data) -> mapSubjectForAdmin(id, data, expectedBranchId) }
            .sortedWith(compareBy<StudySubject> { it.order }.thenBy { it.name })
    }

    fun mapSectionForAdmin(
        id: String,
        data: Map<String, Any?>,
        expectedSubjectId: String
    ): StudySection? {
        if (id.isBlank() || expectedSubjectId.isBlank()) return null

        // Confirmed schema: sections.subjectId is a DocumentReference to subjects/{subjectId}; no branchId field.
        val subjectRef = FirestoreCatalogMapper.extractReferenceTarget(data[Fields.SUBJECT_ID]) ?: return null
        if (subjectRef.collectionId != Collections.SUBJECTS || subjectRef.documentId != expectedSubjectId) {
            return null
        }

        val name = extractText(data, Fields.NAME_AR, Fields.NAME, Fields.TITLE) ?: return null
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE
        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false

        return StudySection(
            id = id,
            subjectId = subjectRef.documentId,
            name = name,
            order = order,
            isPublished = isPublished,
            subjectReferencePath = subjectRef.path
        )
    }

    fun mapSectionsForAdmin(
        documents: List<Pair<String, Map<String, Any?>>>,
        expectedSubjectId: String
    ): List<StudySection> {
        return documents
            .mapNotNull { (id, data) -> mapSectionForAdmin(id, data, expectedSubjectId) }
            .sortedWith(compareBy<StudySection> { it.order }.thenBy { it.name })
    }

    fun mapStudyFileForAdmin(
        id: String,
        data: Map<String, Any?>,
        expectedSectionId: String
    ): StudyFileMetadata? {
        if (id.isBlank() || expectedSectionId.isBlank()) return null

        val sectionRef = FirestoreCatalogMapper.extractReferenceTarget(data[Fields.SECTION_ID]) ?: return null
        if (sectionRef.collectionId != Collections.SECTIONS || sectionRef.documentId != expectedSectionId) {
            return null
        }

        val title = extractText(data, Fields.TITLE, Fields.NAME_AR, Fields.NAME) ?: return null
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE
        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false
        val isArchived = (data[Fields.IS_ARCHIVED] as? Boolean) ?: false
        val description = (data[Fields.DESCRIPTION] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val mimeType = (data[Fields.MIME_TYPE] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Defaults.MIME_TYPE_PDF
        val storageProvider = (data[Fields.STORAGE_PROVIDER] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Defaults.STORAGE_PROVIDER_CLOUDFLARE_R2
        val activeVersionId = (data[Fields.ACTIVE_VERSION_ID] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val activeVersionNumber = (data[Fields.ACTIVE_VERSION_NUMBER] as? Number)?.toInt()?.takeIf { it >= 1 }
        val activeVersionLabel = (data[Fields.ACTIVE_VERSION_LABEL] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val activeVersionPublishedAt = FirestoreCatalogMapper.extractEpochMillis(
            data[Fields.ACTIVE_VERSION_PUBLISHED_AT]
        )
        val createdByUid = (data[Fields.CREATED_BY_UID] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val updatedByUid = (data[Fields.UPDATED_BY_UID] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        return StudyFileMetadata(
            id = id,
            sectionId = sectionRef.documentId,
            sectionReferencePath = sectionRef.path,
            title = title,
            description = description,
            order = order,
            isPublished = isPublished,
            isArchived = isArchived,
            mimeType = mimeType,
            storageProvider = storageProvider,
            activeVersionId = activeVersionId,
            activeVersionNumber = activeVersionNumber,
            activeVersionLabel = activeVersionLabel,
            createdByUid = createdByUid,
            updatedByUid = updatedByUid,
            activeVersionPublishedAtEpochMillis = activeVersionPublishedAt
        )
    }

    fun mapStudyFilesForAdmin(
        documents: List<Pair<String, Map<String, Any?>>>,
        expectedSectionId: String
    ): List<StudyFileMetadata> {
        return documents
            .mapNotNull { (id, data) -> mapStudyFileForAdmin(id, data, expectedSectionId) }
            .sortedWith(
                compareBy<StudyFileMetadata> { it.isArchived }
                    .thenBy { it.order }
                    .thenBy { it.title }
            )
    }

    /**
     * Maps a `study_files/{fileId}/versions/{versionId}` document while strictly validating its
     * parent [Fields.FILE_ID] DocumentReference points to `study_files/{expectedFileId}`.
     */
    fun mapStudyFileVersionForAdmin(
        id: String,
        data: Map<String, Any?>,
        expectedFileId: String
    ): StudyFileVersionMetadata? {
        if (id.isBlank() || expectedFileId.isBlank()) return null

        val fileRef = FirestoreCatalogMapper.extractReferenceTarget(data[Fields.FILE_ID]) ?: return null
        if (fileRef.collectionId != Collections.STUDY_FILES || fileRef.documentId != expectedFileId) {
            return null
        }

        val versionNumber = (data[Fields.VERSION_NUMBER] as? Number)?.toInt()?.takeIf { it >= 1 }
            ?: return null
        val versionLabel = (data[Fields.VERSION_LABEL] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val versionNotes = (data[Fields.VERSION_NOTES] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val r2ObjectKey = (data[Fields.R2_OBJECT_KEY] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val fileSizeBytes = (data[Fields.FILE_SIZE_BYTES] as? Number)?.toLong()?.takeIf { it > 0L }
        val checksumSha256 = (data[Fields.CHECKSUM_SHA256] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false
        val isArchived = (data[Fields.IS_ARCHIVED] as? Boolean) ?: false
        val createdByUid = (data[Fields.CREATED_BY_UID] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val updatedByUid = (data[Fields.UPDATED_BY_UID] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val publishedAt = FirestoreCatalogMapper.extractEpochMillis(data[Fields.PUBLISHED_AT])
        val createdAt = FirestoreCatalogMapper.extractEpochMillis(data[Fields.CREATED_AT])
        val updatedAt = FirestoreCatalogMapper.extractEpochMillis(data[Fields.UPDATED_AT])

        return StudyFileVersionMetadata(
            id = id,
            fileId = fileRef.documentId,
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            versionNotes = versionNotes,
            r2ObjectKey = r2ObjectKey,
            fileSizeBytes = fileSizeBytes,
            checksumSha256 = checksumSha256,
            isPublished = isPublished,
            createdByUid = createdByUid,
            publishedAtEpochMillis = publishedAt,
            createdAtEpochMillis = createdAt,
            fileReferencePath = fileRef.path,
            updatedByUid = updatedByUid,
            updatedAtEpochMillis = updatedAt,
            isArchived = isArchived
        )
    }

    fun mapStudyFileVersionsForAdmin(
        documents: List<Pair<String, Map<String, Any?>>>,
        expectedFileId: String
    ): List<StudyFileVersionMetadata> {
        return documents
            .mapNotNull { (id, data) -> mapStudyFileVersionForAdmin(id, data, expectedFileId) }
            .sortedWith(
                compareByDescending<StudyFileVersionMetadata> { it.versionNumber }
                    .thenByDescending { it.publishedAtEpochMillis ?: 0L }
                    .thenByDescending { it.createdAtEpochMillis ?: 0L }
            )
    }

    fun hasReliableUploadedPdfProof(
        fileId: String,
        versionId: String,
        r2ObjectKey: String?,
        fileSizeBytes: Long?,
        checksumSha256: String? = null
    ): Boolean {
        val cleanFileId = fileId.trim()
        val cleanVersionId = versionId.trim()
        if (cleanFileId.isEmpty() || cleanVersionId.isEmpty()) return false

        val cleanKey = r2ObjectKey?.trim().orEmpty()
        if (cleanKey.isEmpty()) return false
        if (cleanKey.contains("://") ||
            cleanKey.contains("..") ||
            cleanKey.contains("?") ||
            cleanKey.contains("#") ||
            cleanKey.contains("fake", ignoreCase = true) ||
            cleanKey.contains("placeholder", ignoreCase = true)
        ) {
            return false
        }

        val encodedFileId = encodeR2PathSegment(cleanFileId)
        val encodedVersionId = encodeR2PathSegment(cleanVersionId)
        val encodedPrefix = "study-files/$encodedFileId/versions/$encodedVersionId/"
        val rawPrefix = "study-files/$cleanFileId/versions/$cleanVersionId/"
        val matchingPrefix = when {
            cleanKey.startsWith(encodedPrefix) -> encodedPrefix
            cleanKey.startsWith(rawPrefix) -> rawPrefix
            else -> return false
        }
        val fileName = cleanKey.removePrefix(matchingPrefix)
        if (fileName.isBlank() || fileName.contains("/") || !fileName.endsWith(".pdf")) {
            return false
        }

        if (fileSizeBytes == null || fileSizeBytes <= 0L) return false

        val cleanChecksum = checksumSha256?.trim()
        if (!cleanChecksum.isNullOrEmpty() && !SHA256_HEX_REGEX.matches(cleanChecksum)) {
            return false
        }
        return true
    }

    fun extractRegisteredVersionNumbers(data: Map<String, Any?>): Set<Int> {
        val rawList = data[Fields.REGISTERED_VERSION_NUMBERS] as? List<*> ?: return emptySet()
        return rawList.mapNotNull { item ->
            (item as? Number)?.toInt()?.takeIf { it >= 1 }
        }.toSet()
    }

    fun validateStudyFilePublishTransition(
        fileId: String,
        exists: Boolean,
        isArchived: Boolean,
        targetPublished: Boolean
    ) {
        if (!exists) {
            throw AdminException(
                reason = AdminFailureReason.NOT_FOUND,
                message = "Study file '$fileId' does not exist."
            )
        }
        if (targetPublished && isArchived) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                message = "Cannot publish archived study file '$fileId'; unarchive it first."
            )
        }
    }

    private fun encodeR2PathSegment(value: String): String {
        return URLEncoder.encode(value.trim(), Charsets.UTF_8.name()).replace("+", "%20")
    }

    fun buildBranchCreatePayload(
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.NAME to name,
        Fields.ORDER to order,
        Fields.IS_PUBLISHED to isPublished,
        Fields.CREATED_BY_UID to actorUid,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.CREATED_AT to timestampValue,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildSubjectCreatePayload(
        branchReference: Any,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.BRANCH_ID to branchReference,
        Fields.NAME to name,
        Fields.ORDER to order,
        Fields.IS_PUBLISHED to isPublished,
        Fields.CREATED_BY_UID to actorUid,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.CREATED_AT to timestampValue,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildSectionCreatePayload(
        subjectReference: Any,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        // Strict confirmed schema: sections has NO branchId field.
        Fields.SUBJECT_ID to subjectReference,
        Fields.NAME to name,
        Fields.ORDER to order,
        Fields.IS_PUBLISHED to isPublished,
        Fields.CREATED_BY_UID to actorUid,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.CREATED_AT to timestampValue,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildCatalogItemUpdatePayload(
        name: String,
        order: Int,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.NAME to name,
        Fields.ORDER to order,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildOrderUpdatePayload(
        order: Int,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.ORDER to order,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildPublishStateUpdatePayload(
        isPublished: Boolean,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.IS_PUBLISHED to isPublished,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildStudyFileCreatePayload(
        sectionReference: Any,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.SECTION_ID to sectionReference,
        Fields.TITLE to title,
        Fields.DESCRIPTION to description,
        Fields.ORDER to order,
        Fields.IS_PUBLISHED to isPublished,
        Fields.IS_ARCHIVED to false,
        Fields.MIME_TYPE to Defaults.MIME_TYPE_PDF,
        Fields.STORAGE_PROVIDER to Defaults.STORAGE_PROVIDER_CLOUDFLARE_R2,
        // Pointer-only fields remain null until an independent version document is created
        // under `study_files/{fileId}/versions/{versionId}` and published as active.
        Fields.ACTIVE_VERSION_ID to null,
        Fields.ACTIVE_VERSION_NUMBER to null,
        Fields.ACTIVE_VERSION_LABEL to null,
        Fields.ACTIVE_VERSION_PUBLISHED_AT to null,
        Fields.CREATED_BY_UID to actorUid,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.CREATED_AT to timestampValue,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildStudyFileUpdatePayload(
        title: String,
        description: String?,
        order: Int,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        // Intentionally excludes activeVersionId/version/R2 fields so editing file identity
        // never overwrites version history or active version pointers.
        Fields.TITLE to title,
        Fields.DESCRIPTION to description,
        Fields.ORDER to order,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildArchiveStateUpdatePayload(
        isArchived: Boolean,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.IS_ARCHIVED to isArchived,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.UPDATED_AT to timestampValue
    )

    /**
     * Builds the payload for `study_files/{fileId}/versions/{versionId}`.
     * Never writes fake R2 keys, fake download URLs, or personal user/student data.
     */
    fun buildStudyFileVersionCreatePayload(
        fileReference: Any,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        isPublished: Boolean,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.FILE_ID to fileReference,
        Fields.VERSION_NUMBER to versionNumber,
        Fields.VERSION_LABEL to versionLabel,
        Fields.VERSION_NOTES to versionNotes,
        Fields.R2_OBJECT_KEY to null,
        Fields.FILE_SIZE_BYTES to null,
        Fields.CHECKSUM_SHA256 to null,
        Fields.IS_PUBLISHED to isPublished,
        Fields.IS_ARCHIVED to false,
        Fields.PUBLISHED_AT to if (isPublished) timestampValue else null,
        Fields.CREATED_BY_UID to actorUid,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.CREATED_AT to timestampValue,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildStudyFileVersionMetadataUpdatePayload(
        versionLabel: String,
        versionNotes: String?,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        // Preserves versionNumber, fileId parent reference, r2ObjectKey, and publish status.
        Fields.VERSION_LABEL to versionLabel,
        Fields.VERSION_NOTES to versionNotes,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.UPDATED_AT to timestampValue
    )

    fun buildActiveVersionPointerPayload(
        versionId: String,
        versionNumber: Int,
        versionLabel: String,
        publishedAtValue: Any,
        actorUid: String,
        timestampValue: Any
    ): Map<String, Any?> = mapOf(
        Fields.ACTIVE_VERSION_ID to versionId,
        Fields.ACTIVE_VERSION_NUMBER to versionNumber,
        Fields.ACTIVE_VERSION_LABEL to versionLabel,
        Fields.ACTIVE_VERSION_PUBLISHED_AT to publishedAtValue,
        Fields.UPDATED_BY_UID to actorUid,
        Fields.UPDATED_AT to timestampValue
    )

    fun mapThrowableToAdminReason(throwable: Throwable): AdminFailureReason {
        val nestedAdmin = throwable as? AdminException ?: throwable.cause as? AdminException
        if (nestedAdmin != null) return nestedAdmin.reason

        return when (throwable) {
            is FirebaseAuthException -> when (throwable.errorCode) {
                "ERROR_EMAIL_ALREADY_IN_USE" -> AdminFailureReason.EMAIL_ALREADY_IN_USE
                "ERROR_WEAK_PASSWORD" -> AdminFailureReason.WEAK_PASSWORD
                "ERROR_USER_DISABLED" -> AdminFailureReason.ACCOUNT_DISABLED
                "ERROR_USER_NOT_FOUND" -> AdminFailureReason.ACCOUNT_NOT_FOUND
                "ERROR_USER_TOKEN_EXPIRED" -> AdminFailureReason.UNAUTHENTICATED
                "ERROR_INVALID_CREDENTIAL",
                "ERROR_WRONG_PASSWORD",
                "ERROR_INVALID_EMAIL" -> AdminFailureReason.INVALID_CREDENTIALS
                "ERROR_TOO_MANY_REQUESTS" -> AdminFailureReason.TOO_MANY_REQUESTS
                "ERROR_NETWORK_REQUEST_FAILED" -> AdminFailureReason.NETWORK_UNAVAILABLE
                else -> AdminFailureReason.UNKNOWN
            }
            is FirebaseNetworkException -> AdminFailureReason.NETWORK_UNAVAILABLE
            is FirebaseFirestoreException -> when (throwable.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> AdminFailureReason.PERMISSION_DENIED
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> AdminFailureReason.UNAUTHENTICATED
                FirebaseFirestoreException.Code.NOT_FOUND -> AdminFailureReason.NOT_FOUND
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> AdminFailureReason.NETWORK_UNAVAILABLE
                else -> AdminFailureReason.UNKNOWN
            }
            is IOException -> AdminFailureReason.NETWORK_UNAVAILABLE
            else -> AdminFailureReason.UNKNOWN
        }
    }

    private fun extractText(data: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = (data[key] as? String)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }
}
