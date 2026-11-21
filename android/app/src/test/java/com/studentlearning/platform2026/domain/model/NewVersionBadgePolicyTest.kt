package com.studentlearning.platform2026.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewVersionBadgePolicyTest {

    private fun sampleFile(
        activeVersionId: String? = "v2",
        publishedAt: Long? = 1_700_000_000_000L
    ) = StudyFileMetadata(
        id = "file-1",
        sectionId = "sec-1",
        title = "ملزمة الفيزياء",
        order = 1,
        isPublished = true,
        isArchived = false,
        activeVersionId = activeVersionId,
        activeVersionNumber = if (activeVersionId != null) 2 else null,
        activeVersionLabel = if (activeVersionId != null) "2.0" else null,
        activeVersionPublishedAtEpochMillis = publishedAt
    )

    private fun savedCopy(versionId: String) = LocalDownloadedFileVersion(
        fileId = "file-1",
        versionId = versionId,
        versionNumber = if (versionId == "v1") 1 else 2,
        versionLabel = versionId,
        fileTitle = "ملزمة الفيزياء",
        sectionId = "sec-1",
        localFilePath = "/private/$versionId.pdf",
        fileSizeBytes = 4096L,
        checksumSha256 = "abc123",
        downloadedAtEpochMillis = 1_700_000_050_000L
    )

    @Test
    fun `shows badge within 30 days and expires cleanly at one-month boundary`() {
        val publishedAt = 1_700_000_000_000L
        val file = sampleFile(publishedAt = publishedAt)

        assertTrue(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = null,
                nowEpochMillis = publishedAt + 1_000L
            )
        )
        assertTrue(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = StudyFileOfflineState(fileId = "file-1", activeVersionId = "v2"),
                nowEpochMillis = publishedAt + NewVersionBadgePolicy.ONE_MONTH_WINDOW_MILLIS - 1L
            )
        )
        assertFalse(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = StudyFileOfflineState(fileId = "file-1", activeVersionId = "v2"),
                nowEpochMillis = publishedAt + NewVersionBadgePolicy.ONE_MONTH_WINDOW_MILLIS
            )
        )
        assertFalse(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = null,
                nowEpochMillis = publishedAt + NewVersionBadgePolicy.ONE_MONTH_WINDOW_MILLIS + 86_400_000L
            )
        )
    }

    @Test
    fun `returns false when active version or publish timestamp is missing and keeps badge visible on slow device clock`() {
        val publishedAt = 1_700_000_000_000L

        assertFalse(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = sampleFile(activeVersionId = null, publishedAt = publishedAt),
                offlineState = null,
                nowEpochMillis = publishedAt + 1000L
            )
        )
        assertFalse(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = sampleFile(activeVersionId = "v2", publishedAt = null),
                offlineState = null,
                nowEpochMillis = publishedAt + 1000L
            )
        )
        assertTrue(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = sampleFile(activeVersionId = "v2", publishedAt = publishedAt + 2L * 60L * 60L * 1000L),
                offlineState = null,
                nowEpochMillis = publishedAt
            )
        )
    }

    @Test
    fun `hides badge only on device that downloaded active version while keeping older or failed downloads visible`() {
        val publishedAt = 1_700_000_000_000L
        val now = publishedAt + 2_000_000L
        val file = sampleFile(activeVersionId = "v2", publishedAt = publishedAt)

        // Device A holds only older v1 and failed/cancelled attempts for v2 -> badge stays visible.
        val deviceAOfflineState = StudyFileOfflineState(
            fileId = "file-1",
            activeVersionId = "v2",
            activeVersionDownload = DownloadTaskState(
                fileId = "file-1",
                versionId = "v2",
                status = DownloadStatus.FAILED,
                failureReason = DownloadFailureReason.NETWORK_UNAVAILABLE
            ),
            savedVersions = listOf(savedCopy("v1"))
        )
        assertTrue(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = deviceAOfflineState,
                nowEpochMillis = now
            )
        )

        // Cancelled / in-progress download before verification also keeps badge visible.
        val deviceACancellingState = deviceAOfflineState.copy(
            activeVersionDownload = DownloadTaskState(
                fileId = "file-1",
                versionId = "v2",
                status = DownloadStatus.CANCELLED
            )
        )
        assertTrue(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = deviceACancellingState,
                nowEpochMillis = now
            )
        )

        // Device B downloaded verified active v2 (even alongside v1) -> badge hides on Device B only.
        val deviceBOfflineState = StudyFileOfflineState(
            fileId = "file-1",
            activeVersionId = "v2",
            savedVersions = listOf(savedCopy("v1"), savedCopy("v2"))
        )
        assertFalse(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = deviceBOfflineState,
                nowEpochMillis = now
            )
        )
        // Confirm Device A remains unaffected for the exact same file metadata & timestamp.
        assertTrue(
            NewVersionBadgePolicy.shouldShowNewVersionBadge(
                file = file,
                offlineState = deviceAOfflineState,
                nowEpochMillis = now
            )
        )
    }

    @Test
    fun `resolveTrustedNowEpochMillis ignores abrupt forward and backward wall clock jumps`() {
        val anchorWall = 1_700_000_000_000L
        val anchorMono = 50_000L
        val fiveMinutesMillis = 5L * 60L * 1000L

        // Normal progression stays accurate within NTP tolerance.
        val normalNow = NewVersionBadgePolicy.resolveTrustedNowEpochMillis(
            reportedWallEpochMillis = anchorWall + fiveMinutesMillis + 2_000L,
            anchorWallEpochMillis = anchorWall,
            anchorMonotonicMillis = anchorMono,
            currentMonotonicMillis = anchorMono + fiveMinutesMillis
        )
        assertEquals(anchorWall + fiveMinutesMillis + 2_000L, normalNow)

        // User pushes device clock forward +45 days while only 5 minutes actually elapsed.
        val fortyFiveDaysMillis = 45L * 24L * 60L * 60L * 1000L
        val forwardTamperedNow = NewVersionBadgePolicy.resolveTrustedNowEpochMillis(
            reportedWallEpochMillis = anchorWall + fortyFiveDaysMillis,
            anchorWallEpochMillis = anchorWall,
            anchorMonotonicMillis = anchorMono,
            currentMonotonicMillis = anchorMono + fiveMinutesMillis
        )
        assertEquals(anchorWall + fiveMinutesMillis, forwardTamperedNow)

        // User pulls device clock backward -4 hours while 5 minutes actually elapsed.
        val fourHoursMillis = 4L * 60L * 60L * 1000L
        val backwardTamperedNow = NewVersionBadgePolicy.resolveTrustedNowEpochMillis(
            reportedWallEpochMillis = anchorWall - fourHoursMillis,
            anchorWallEpochMillis = anchorWall,
            anchorMonotonicMillis = anchorMono,
            currentMonotonicMillis = anchorMono + fiveMinutesMillis
        )
        assertEquals(anchorWall + fiveMinutesMillis, backwardTamperedNow)
    }
}
