package com.studentlearning.platform2026.domain.model

import kotlin.math.abs

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
 * 4. Untrusted device clock skew (backward/slow clock relative to server publish timestamp or abrupt
 *    in-session wall-clock jumps forward/backward) never prematurely hides the badge.
 */
object NewVersionBadgePolicy {

    const val ONE_MONTH_WINDOW_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
    const val MAX_TRUSTED_CLOCK_JUMP_DRIFT_MILLIS: Long = 15L * 60L * 1000L

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

        // If the device wall clock is behind the server publish timestamp, do not treat negative
        // elapsed time as expired or invalid — preserve visibility until 30 days after publishedAt.
        val elapsedMillis = (nowEpochMillis - publishedAt).coerceAtLeast(0L)
        return elapsedMillis < ONE_MONTH_WINDOW_MILLIS
    }

    fun resolveTrustedNowEpochMillis(
        reportedWallEpochMillis: Long,
        anchorWallEpochMillis: Long,
        anchorMonotonicMillis: Long,
        currentMonotonicMillis: Long
    ): Long {
        val elapsedMonotonic = (currentMonotonicMillis - anchorMonotonicMillis).coerceAtLeast(0L)
        val projectedNow = anchorWallEpochMillis + elapsedMonotonic
        if (reportedWallEpochMillis <= 0L) {
            return projectedNow
        }
        val driftMillis = abs(reportedWallEpochMillis - projectedNow)
        return when {
            reportedWallEpochMillis < projectedNow -> projectedNow
            driftMillis > MAX_TRUSTED_CLOCK_JUMP_DRIFT_MILLIS -> projectedNow
            else -> reportedWallEpochMillis
        }
    }
}
