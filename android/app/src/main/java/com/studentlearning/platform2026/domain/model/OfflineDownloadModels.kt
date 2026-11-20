package com.studentlearning.platform2026.domain.model

enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isInProgress: Boolean
        get() = this == QUEUED || this == DOWNLOADING
}

enum class DownloadFailureReason {
    UNAUTHENTICATED_GUEST,
    UNAUTHORIZED_ROLE,
    NO_PUBLISHED_VERSION,
    SECURE_BROKER_NOT_CONFIGURED,
    UNAUTHORIZED_ACCESS,
    NETWORK_UNAVAILABLE,
    SERVER_ERROR,
    INSUFFICIENT_STORAGE,
    STORAGE_WRITE_ERROR,
    INTEGRITY_CHECK_FAILED,
    CANCELLED,
    UNKNOWN
}

class DownloadException(
    val reason: DownloadFailureReason,
    override val message: String? = null,
    override val cause: Throwable? = null
) : Exception(message, cause)

data class OfflineDownloadRequest(
    val fileId: String,
    val versionId: String,
    val versionNumber: Int? = null,
    val versionLabel: String? = null,
    val fileTitle: String,
    val sectionId: String? = null,
    val expectedFileSizeBytes: Long? = null,
    val expectedChecksumSha256: String? = null
)

/**
 * Metadata for a completed, integrity-verified offline PDF version stored strictly on this device.
 *
 * Keyed by ([fileId], [versionId]) rather than mutable titles or Firebase UIDs so:
 * - Multiple versions of the same file coexist safely without overwriting older versions.
 * - Distinct files with identical titles never collide.
 * - Signing out or switching accounts never deletes or leaks device-local downloads.
 */
data class LocalDownloadedFileVersion(
    val fileId: String,
    val versionId: String,
    val versionNumber: Int? = null,
    val versionLabel: String? = null,
    val fileTitle: String,
    val sectionId: String? = null,
    val localFilePath: String,
    val fileSizeBytes: Long,
    val checksumSha256: String,
    val downloadedAtEpochMillis: Long
)

data class DownloadTaskState(
    val fileId: String,
    val versionId: String,
    val versionNumber: Int? = null,
    val versionLabel: String? = null,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val progressPercent: Int? = null,
    val failureReason: DownloadFailureReason? = null,
    val updatedAtEpochMillis: Long = 0L,
    val taskId: String? = null
)

data class StudyFileOfflineState(
    val fileId: String,
    val activeVersionId: String?,
    val activeVersionDownload: DownloadTaskState? = null,
    val savedVersions: List<LocalDownloadedFileVersion> = emptyList()
) {
    val activeVersionSavedCopy: LocalDownloadedFileVersion?
        get() = if (activeVersionId.isNullOrBlank()) {
            null
        } else {
            savedVersions.firstOrNull { it.versionId == activeVersionId }
        }

    val olderSavedVersions: List<LocalDownloadedFileVersion>
        get() = if (activeVersionId.isNullOrBlank()) {
            savedVersions
        } else {
            savedVersions.filter { it.versionId != activeVersionId }
        }

    val hasActiveVersionLocalCopy: Boolean
        get() = activeVersionSavedCopy != null

    val hasAnyLocalCopy: Boolean
        get() = savedVersions.isNotEmpty()

    val latestSavedVersion: LocalDownloadedFileVersion?
        get() = activeVersionSavedCopy ?: savedVersions.firstOrNull()

    val hasNewerOnlineVersionWhileHoldingOlderCopy: Boolean
        get() = !activeVersionId.isNullOrBlank() &&
            activeVersionSavedCopy == null &&
            olderSavedVersions.isNotEmpty()
}
