package com.studentlearning.platform2026.domain.model

/**
 * Parent administrative metadata for an educational PDF file stored at `study_files/{fileId}`.
 *
 * File identity ([id], [sectionId], [title], [description], [order], [isPublished], [isArchived])
 * is kept separate from version-specific and binary storage fields. Future independent versions
 * will live under `study_files/{fileId}/versions/{versionId}` ([StudyFileVersionMetadata]),
 * while this parent document holds only optional pointer fields ([activeVersionId],
 * [activeVersionNumber], [activeVersionLabel]) once an actual version is published.
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
    val updatedByUid: String? = null
) {
    val hasActiveVersion: Boolean
        get() = !activeVersionId.isNullOrBlank()

    companion object {
        const val MIME_TYPE_PDF = "application/pdf"
        const val STORAGE_PROVIDER_CLOUDFLARE_R2 = "cloudflare_r2"
    }
}

/**
 * Future independent version contract for documents stored under
 * `study_files/{fileId}/versions/{versionId}`.
 *
 * Defined as a structural contract only so Phase 5/8 can attach PDF uploads and multiple
 * immutable versions without altering parent file identity. Not instantiated with fake data
 * in Phase 4.
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
    val createdAtEpochMillis: Long? = null
)
