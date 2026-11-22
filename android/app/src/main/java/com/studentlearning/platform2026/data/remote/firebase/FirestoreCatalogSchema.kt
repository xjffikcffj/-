package com.studentlearning.platform2026.data.remote.firebase

/**
 * Confirmed Firestore catalog schema contract:
 *
 * - Collection `branches`: documents (e.g., `scientific`, `literary`) with `name` (Arabic String),
 *   `isPublished` (Boolean), and `order` (Number).
 * - Collection `subjects`: `branchId` (DocumentReference -> `branches/{branchId}`),
 *   `name` (Arabic String), `isPublished` (Boolean), and `order` (Number).
 * - Collection `sections`: `subjectId` (DocumentReference -> `subjects/{subjectId}`),
 *   `name` (Arabic String), `isPublished` (Boolean), and `order` (Number). No `branchId` field.
 * - Collection `study_files`: administrative PDF metadata attached via `sectionId`
 *   (DocumentReference -> `sections/{sectionId}`), prepared for future Cloudflare R2 storage
 *   without fake URLs or placeholder download paths.
 */
object FirestoreCatalogSchema {
    object Collections {
        const val BRANCHES = "branches"
        const val SUBJECTS = "subjects"
        const val SECTIONS = "sections"
        const val STUDY_FILES = "study_files"
        const val AUDIT_LOGS = "audit_logs"
    }

    object Subcollections {
        const val VERSIONS = "versions"
    }

    object Fields {
        const val IS_PUBLISHED = "isPublished"
        const val IS_ARCHIVED = "isArchived"
        const val ORDER = "order"
        const val BRANCH_ID = "branchId"
        const val SUBJECT_ID = "subjectId"
        const val SECTION_ID = "sectionId"
        const val NAME = "name"
        const val NAME_AR = "nameAr"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val MIME_TYPE = "mimeType"
        const val STORAGE_PROVIDER = "storageProvider"

        // Pointer fields on parent `study_files/{fileId}` pointing to the single active
        // published document inside `study_files/{fileId}/versions/{versionId}`.
        const val ACTIVE_VERSION_ID = "activeVersionId"
        const val ACTIVE_VERSION_NUMBER = "activeVersionNumber"
        const val ACTIVE_VERSION_LABEL = "activeVersionLabel"
        const val ACTIVE_VERSION_PUBLISHED_AT = "activeVersionPublishedAt"
        const val REGISTERED_VERSION_NUMBERS = "registeredVersionNumbers"

        // Version-specific fields belonging inside `study_files/{fileId}/versions/{versionId}`.
        const val FILE_ID = "fileId"
        const val VERSION_NUMBER = "versionNumber"
        const val VERSION_LABEL = "versionLabel"
        const val VERSION_NOTES = "versionNotes"
        const val R2_OBJECT_KEY = "r2ObjectKey"
        const val FILE_SIZE_BYTES = "fileSizeBytes"
        const val CHECKSUM_SHA256 = "checksumSha256"
        const val PUBLISHED_AT = "publishedAt"

        const val CREATED_BY_UID = "createdByUid"
        const val UPDATED_BY_UID = "updatedByUid"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
    }

    object Claims {
        const val ROLE = "role"
        const val ROLE_OWNER = "owner"
        const val ROLE_ADMIN = "admin"
    }

    object Defaults {
        const val MIME_TYPE_PDF = "application/pdf"
        const val STORAGE_PROVIDER_CLOUDFLARE_R2 = "cloudflare_r2"
    }
}
