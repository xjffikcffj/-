package com.studentlearning.platform2026.domain.model

/**
 * Evaluates device-local visibility of the «إصدار جديد» badge for a study file.
 *
 * Rules enforced purely locally (never synced to Firestore or tied to user UIDs):
 * 1. The file must have a valid [StudyFileMetadata.activeVersionId] and publish timestamp.
 * 2. The badge remains active for 1 month (30 days) from [StudyFileMetadata.activeVersionPublishedAtEpochMillis],
 *    then expires automatically.
 * 3. Downloading and saving the active version locally hides the badge ON THIS DEVICE ONLY.
 *    Failed, cancelled, or in-progress downloads, as well as online viewing without completing
 *    a local save, keep the badge visible while the 30-day window is active.
 */
object NewVersionBadgePolicy {

    const val ONE_MONTH_WINDOW_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_FUTURE_CLOCK_SKEW_MILLIS: Long = 5L * 60L * 1000L

    fun shouldShowNewVersionBadge(
        file: StudyFileMetadata,
        offlineState: StudyFileOfflineState?,
        nowEpochMillis: Long
    ): Boolean {
        if (!file.hasActiveVersion) return false
        val publishedAt = file.activeVersionPublishedAtEpochMillis ?: return false
        if (publishedAt <= 0L || nowEpochMillis <= 0L) return false

        // Hides ONLY on this device once the active version is completely downloaded and verified locally.
        if (offlineState?.activeVersionSavedCopy != null) return false

        val elapsedMillis = nowEpochMillis - publishedAt
        if (elapsedMillis < -MAX_FUTURE_CLOCK_SKEW_MILLIS) return false
        return elapsedMillis < ONE_MONTH_WINDOW_MILLIS
    }
}
