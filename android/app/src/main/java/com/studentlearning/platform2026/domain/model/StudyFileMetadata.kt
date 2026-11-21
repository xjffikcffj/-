package com.studentlearning.platform2026.domain.model

import com.studentlearning.platform2026.domain.validation.AdminCatalogValidator

/**
 * Explicit tri-state archive status matching Backend D2 fail-closed semantics:
 * - [UNARCHIVED]: explicitly `isArchived == false`
 * - [ARCHIVED]: explicitly `isArchived == true`
 * - [UNSPECIFIED]: missing, null, invalid type, or unrecognized value (never coerced to [UNARCHIVED])
 */
enum class ArchiveStatus {
    UNARCHIVED,
    ARCHIVED,
    UNSPECIFIED;

    companion object {
        fun fromIsArchivedValue(rawValue: Any?): ArchiveStatus = when (rawValue) {
            false -> UNARCHIVED
            true -> ARCHIVED
            else -> UNSPECIFIED
        }

        fun fromBackend(rawArchiveStatus: Any?, rawIsArchived: Any?): ArchiveStatus {
            val normalizedStatus = (rawArchiveStatus as? String)?.trim()
            return when {
                normalizedStatus == "UNARCHIVED" && rawIsArchived == false -> UNARCHIVED
                normalizedStatus == "ARCHIVED" && rawIsArchived == true -> ARCHIVED
                else -> UNSPECIFIED
            }
        }
    }
}

/**
 * Parent administrative metadata for an educational PDF file stored at `study_files/{fileId}`.
 *
 * File identity ([id], [sectionId], [title], [description], [order], [isPublished], [archiveStatus])
 * is kept separate from version-specific and binary storage fields. Independent versions live under
 * `study_files/{fileId}/versions/{versionId}` ([StudyFileVersionMetadata]), while this parent
 * document holds pointer fields ([activeVersionId], [activeVersionNumber], [activeVersionLabel],
 * [activeVersionPublishedAtEpochMillis]) referencing ONLY the single currently published active version.
 *
 * No fake PDF binaries, fake version documents, or placeholder Cloudflare R2 keys are created here.
 */
data class StudyFileMetadata(
    val id: String,
    val sectionId: String,
    val sectionReferencePath: String = "sections/$sectionId",
    val title: String,
    val description: String? = null,
    val order: Int,
    val isPublished: Boolean,
    val archiveStatus: ArchiveStatus = ArchiveStatus.UNSPECIFIED,
    val mimeType: String = MIME_TYPE_PDF,
    val storageProvider: String = STORAGE_PROVIDER_CLOUDFLARE_R2,
    val activeVersionId: String? = null,
    val activeVersionNumber: Int? = null,
    val activeVersionLabel: String? = null,
    val createdByUid: String? = null,
    val updatedByUid: String? = null,
    val activeVersionPublishedAtEpochMillis: Long? = null,
    val activeVersionFileSizeBytes: Long? = null,
    val activeVersionChecksumSha256: String? = null
) {
    val isArchived: Boolean
        get() = archiveStatus == ArchiveStatus.ARCHIVED

    val isExplicitlyUnarchived: Boolean
        get() = archiveStatus == ArchiveStatus.UNARCHIVED

    val isExplicitlyArchived: Boolean
        get() = archiveStatus == ArchiveStatus.ARCHIVED

    val isArchiveStatusUnspecified: Boolean
        get() = archiveStatus == ArchiveStatus.UNSPECIFIED

    val hasActiveVersion: Boolean
        get() = !activeVersionId.isNullOrBlank()

    val hasValidActiveVersionForPublish: Boolean
        get() = !activeVersionId.isNullOrBlank() &&
            activeVersionNumber != null &&
            activeVersionNumber >= 1

    companion object {
        const val MIME_TYPE_PDF = "application/pdf"
        const val STORAGE_PROVIDER_CLOUDFLARE_R2 = "cloudflare_r2"
    }
}

/**
 * Independent immutable-identity version record stored under
 * `study_files/{fileId}/versions/{versionId}`.
 *
 * Every version is bound strictly to its parent [fileId] ([fileReferencePath]) and maps to an
 * independent R2 object path keyed by ([fileId], [id]) so older versions remain preserved and
 * never overwritten when a new version is published. Contains zero student/UID download data.
 */
data class StudyFileVersionMetadata(
    val id: String,
    val fileId: String,
    val versionNumber: Int,
    val versionLabel: String,
    val versionNotes: String? = null,
    val r2ObjectKey: String? = null,
    val fileSizeBytes: Long? = null,
    val checksumSha256: String? = null,
    val isPublished: Boolean = false,
    val createdByUid: String? = null,
    val publishedAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long? = null,
    val fileReferencePath: String = "study_files/$fileId",
    val updatedByUid: String? = null,
    val updatedAtEpochMillis: Long? = null,
    val archiveStatus: ArchiveStatus = ArchiveStatus.UNSPECIFIED,
    val binaryVerificationStatus: String? = null,
    val verifiedUploadSessionId: String? = null,
    val verifiedAttemptId: String? = null
) {
    val isArchived: Boolean
        get() = archiveStatus == ArchiveStatus.ARCHIVED

    val isExplicitlyUnarchived: Boolean
        get() = archiveStatus == ArchiveStatus.UNARCHIVED

    val isExplicitlyArchived: Boolean
        get() = archiveStatus == ArchiveStatus.ARCHIVED

    val isArchiveStatusUnspecified: Boolean
        get() = archiveStatus == ArchiveStatus.UNSPECIFIED

    val hasUploadedPdfBinary: Boolean
        get() {
            if (binaryVerificationStatus == "VERIFIED") {
                if (!r2ObjectKey.isNullOrBlank()) {
                    return AdminCatalogValidator.hasReliableUploadedPdfProof(
                        fileId = fileId,
                        versionId = id,
                        r2ObjectKey = r2ObjectKey,
                        fileSizeBytes = fileSizeBytes,
                        checksumSha256 = checksumSha256
                    )
                }
                return AdminCatalogValidator.hasVerifiedServerPdfMetadata(
                    fileId = fileId,
                    versionId = id,
                    fileSizeBytes = fileSizeBytes,
                    checksumSha256 = checksumSha256,
                    verifiedUploadSessionId = verifiedUploadSessionId,
                    verifiedAttemptId = verifiedAttemptId
                )
            }
            if (binaryVerificationStatus != null) {
                return false
            }
            return AdminCatalogValidator.hasReliableUploadedPdfProof(
                fileId = fileId,
                versionId = id,
                r2ObjectKey = r2ObjectKey,
                fileSizeBytes = fileSizeBytes,
                checksumSha256 = checksumSha256
            )
        }
}
