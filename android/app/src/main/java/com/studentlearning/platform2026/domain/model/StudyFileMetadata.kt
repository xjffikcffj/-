package com.studentlearning.platform2026.domain.model

/**
 * Parent administrative metadata for an educational PDF file stored at `study_files/{fileId}`.
 *
 * File identity ([id], [sectionId], [title], [description], [order], [isPublished], [isArchived])
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
    val isArchived: Boolean = false,
    val mimeType: String = MIME_TYPE_PDF,
    val storageProvider: String = STORAGE_PROVIDER_CLOUDFLARE_R2,
    val activeVersionId: String? = null,
    val activeVersionNumber: Int? = null,
    val activeVersionLabel: String? = null,
    val createdByUid: String? = null,
    val updatedByUid: String? = null,
    val activeVersionPublishedAtEpochMillis: Long? = null
) {
    val hasActiveVersion: Boolean
        get() = !activeVersionId.isNullOrBlank()

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
    val isArchived: Boolean = false
) {
    val hasUploadedPdfBinary: Boolean
        get() {
            val key = r2ObjectKey?.trim().orEmpty()
            return key.isNotEmpty() &&
                (fileSizeBytes ?: 0L) > 0L &&
                !key.contains("://") &&
                !key.contains("..")
        }
}
