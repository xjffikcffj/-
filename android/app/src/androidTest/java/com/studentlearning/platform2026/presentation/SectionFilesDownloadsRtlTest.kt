package com.studentlearning.platform2026.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState
import com.studentlearning.platform2026.presentation.downloads.DownloadsScreenContent
import com.studentlearning.platform2026.presentation.downloads.DownloadsUiState
import com.studentlearning.platform2026.presentation.library.SectionFilesScreenContent
import com.studentlearning.platform2026.presentation.library.SectionFilesUiState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SectionFilesDownloadsRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleFile(
        id: String = "file-1",
        title: String = "ملخص الأعداد المركبة",
        versionId: String = "v2",
        versionNumber: Int = 2,
        versionLabel: String = "v2"
    ) = StudyFileMetadata(
        id = id,
        sectionId = "sec-1",
        title = title,
        description = "ملاحظات شاملة",
        order = 1,
        isPublished = true,
        isArchived = false,
        activeVersionId = versionId,
        activeVersionNumber = versionNumber,
        activeVersionLabel = versionLabel
    )

    @Test
    fun notDownloadedFile_displaysBrowseAndDownloadButtons() {
        var browsed = false
        var downloadRequested = false
        val file = sampleFile()

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionFilesScreenContent(
                    uiState = SectionFilesUiState.Content(
                        files = listOf(file),
                        offlineStates = emptyMap(),
                        canDownloadOfflineFiles = true
                    ),
                    onRetry = {},
                    onOpenStudyVersion = { _, _, _ -> browsed = true },
                    onStartDownload = { downloadRequested = true }
                )
            }
        }

        composeTestRule.onNodeWithText("تصفح").assertIsDisplayed().performClick()
        assertTrue(browsed)

        composeTestRule.onNodeWithText("تنزيل").assertIsDisplayed().performClick()
        assertTrue(downloadRequested)
    }

    @Test
    fun downloadingFile_displaysProgressPercentAndCancelButton() {
        var cancelled = false
        val file = sampleFile()
        val offlineState = StudyFileOfflineState(
            fileId = file.id,
            activeVersionId = file.activeVersionId,
            activeVersionDownload = DownloadTaskState(
                fileId = file.id,
                versionId = "v2",
                status = DownloadStatus.DOWNLOADING,
                bytesDownloaded = 650,
                totalBytes = 1000,
                progressPercent = 65
            )
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionFilesScreenContent(
                    uiState = SectionFilesUiState.Content(
                        files = listOf(file),
                        offlineStates = mapOf(file.id to offlineState),
                        canDownloadOfflineFiles = true
                    ),
                    onRetry = {},
                    onOpenStudyVersion = { _, _, _ -> },
                    onCancelDownload = { cancelled = true }
                )
            }
        }

        composeTestRule.onNodeWithText("جاري التنزيل… 65%").assertIsDisplayed()
        composeTestRule.onNodeWithText("إلغاء").assertIsDisplayed().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun downloadedFile_displaysDownloadedBadgeAndOpensLocalVersionOffline() {
        var openedVersionId: String? = null
        val file = sampleFile(versionId = "v2", versionNumber = 2, versionLabel = "v2")
        val savedV2 = LocalDownloadedFileVersion(
            fileId = file.id,
            versionId = "v2",
            versionNumber = 2,
            versionLabel = "v2",
            fileTitle = file.title,
            sectionId = "sec-1",
            localFilePath = "/data/user/0/app/files/v2.pdf",
            fileSizeBytes = 20_480L,
            checksumSha256 = "abc",
            downloadedAtEpochMillis = 1_700_000_000_000L
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionFilesScreenContent(
                    uiState = SectionFilesUiState.Content(
                        files = listOf(file),
                        offlineStates = mapOf(
                            file.id to StudyFileOfflineState(
                                fileId = file.id,
                                activeVersionId = "v2",
                                savedVersions = listOf(savedV2)
                            )
                        ),
                        canDownloadOfflineFiles = true
                    ),
                    onRetry = {},
                    onOpenStudyVersion = { _, _, versionId -> openedVersionId = versionId }
                )
            }
        }

        composeTestRule.onNodeWithText("تم التنزيل").assertIsDisplayed()
        composeTestRule.onNodeWithText("فتح دون اتصال").assertIsDisplayed().performClick()
        assertEquals("v2", openedVersionId)
    }

    @Test
    fun olderSavedVersion_remainsOpenableAlongsideNewerVersionDownloadAction() {
        var openedVersionId: String? = null
        var startedNewVersionDownload = false
        val file = sampleFile(versionId = "v2", versionNumber = 2, versionLabel = "v2")
        val savedV1 = LocalDownloadedFileVersion(
            fileId = file.id,
            versionId = "v1",
            versionNumber = 1,
            versionLabel = "v1",
            fileTitle = file.title,
            sectionId = "sec-1",
            localFilePath = "/data/user/0/app/files/v1.pdf",
            fileSizeBytes = 10_240L,
            checksumSha256 = "def",
            downloadedAtEpochMillis = 1_700_000_000_000L
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionFilesScreenContent(
                    uiState = SectionFilesUiState.Content(
                        files = listOf(file),
                        offlineStates = mapOf(
                            file.id to StudyFileOfflineState(
                                fileId = file.id,
                                activeVersionId = "v2",
                                savedVersions = listOf(savedV1)
                            )
                        ),
                        canDownloadOfflineFiles = true
                    ),
                    onRetry = {},
                    onOpenStudyVersion = { _, _, versionId -> openedVersionId = versionId },
                    onStartDownload = { startedNewVersionDownload = true }
                )
            }
        }

        composeTestRule.onNodeWithText("فتح النسخة المحفوظة (v1)").assertIsDisplayed().performClick()
        assertEquals("v1", openedVersionId)

        composeTestRule.onNodeWithText("تنزيل").assertIsDisplayed().performClick()
        assertTrue(startedNewVersionDownload)
    }

    @Test
    fun guestPromptDialogAndFailedRetry_renderArabicMessages() {
        var signInClicked = false
        var retried = false
        val file = sampleFile()

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionFilesScreenContent(
                    uiState = SectionFilesUiState.Content(
                        files = listOf(file),
                        offlineStates = mapOf(
                            file.id to StudyFileOfflineState(
                                fileId = file.id,
                                activeVersionId = "v2",
                                activeVersionDownload = DownloadTaskState(
                                    fileId = file.id,
                                    versionId = "v2",
                                    status = DownloadStatus.FAILED,
                                    failureReason = DownloadFailureReason.INSUFFICIENT_STORAGE
                                )
                            )
                        ),
                        canDownloadOfflineFiles = false,
                        guestDownloadPromptFileTitle = file.title
                    ),
                    onRetry = {},
                    onOpenStudyVersion = { _, _, _ -> },
                    onRetryDownload = { retried = true },
                    onNavigateToSignIn = { signInClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("تسجيل الدخول مطلوب للتنزيل").assertIsDisplayed()
        composeTestRule.onNodeWithText("لا توجد مساحة تخزين كافية على الجهاز لإتمام التنزيل.").assertIsDisplayed()
        composeTestRule.onNodeWithText("إعادة المحاولة").assertIsDisplayed().performClick()
        assertTrue(retried)
        composeTestRule.onNodeWithText("تسجيل الدخول").assertIsDisplayed().performClick()
        assertTrue(signInClicked)
    }

    @Test
    fun catalogNetworkError_stillShowsSectionOfflineSavedFiles() {
        var openedOfflineVersion: String? = null
        val savedOffline = LocalDownloadedFileVersion(
            fileId = "file-1",
            versionId = "v1",
            versionNumber = 1,
            versionLabel = "v1",
            fileTitle = "ملخص محفوظ",
            sectionId = "sec-1",
            localFilePath = "/data/user/0/app/files/v1.pdf",
            fileSizeBytes = 12_000L,
            checksumSha256 = "sha",
            downloadedAtEpochMillis = 1_700_000_000_000L
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionFilesScreenContent(
                    uiState = SectionFilesUiState.Error(
                        reason = CatalogFailureReason.NETWORK_UNAVAILABLE,
                        offlineSavedFiles = listOf(savedOffline)
                    ),
                    onRetry = {},
                    onOpenStudyVersion = { _, _, versionId -> openedOfflineVersion = versionId }
                )
            }
        }

        composeTestRule.onNodeWithText("ملخص محفوظ").assertIsDisplayed().performClick()
        assertEquals("v1", openedOfflineVersion)
    }

    @Test
    fun newVersionBadge_displaysOnSectionFileCardAndForwardsVersionLabel() {
        var openedLabel: String? = null
        val file = sampleFile(versionId = "v2", versionNumber = 2, versionLabel = "2.0")

        composeTestRule.setContent {
            HaqibatiTheme {
                SectionFilesScreenContent(
                    uiState = SectionFilesUiState.Content(
                        files = listOf(file),
                        offlineStates = emptyMap(),
                        canDownloadOfflineFiles = true,
                        newVersionBadgeFileIds = setOf(file.id)
                    ),
                    onRetry = {},
                    onOpenStudyVersion = { _, _, _ -> },
                    onOpenStudyVersionWithLabel = { _, _, _, versionLabel -> openedLabel = versionLabel }
                )
            }
        }

        composeTestRule.onNodeWithText("إصدار جديد").assertIsDisplayed()
        composeTestRule.onNodeWithText("الإصدار 2.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("تصفح").performClick()
        assertEquals("2.0", openedLabel)
    }

    @Test
    fun downloadsScreen_deleteIconRequiresConfirmationBeforeDeletingVersion() {
        val deletedPairs = mutableListOf<Pair<String, String>>()
        val savedV1 = LocalDownloadedFileVersion(
            fileId = "file-1",
            versionId = "v1",
            versionNumber = 1,
            versionLabel = "1.0",
            fileTitle = "ملخص الأعداد المركبة",
            sectionId = "sec-1",
            localFilePath = "/data/user/0/app/files/v1.pdf",
            fileSizeBytes = 10_240L,
            checksumSha256 = "sha",
            downloadedAtEpochMillis = 1_700_000_000_000L
        )

        composeTestRule.setContent {
            HaqibatiTheme {
                DownloadsScreenContent(
                    uiState = DownloadsUiState(
                        canDownloadOfflineFiles = true,
                        savedVersions = listOf(savedV1)
                    ),
                    onDeleteLocalVersion = { fileId, versionId ->
                        deletedPairs.add(fileId to versionId)
                    }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("حذف النسخة المحفوظة محلياً").performClick()
        composeTestRule.onNodeWithText("تأكيد حذف النسخة المحلية").assertIsDisplayed()
        assertTrue(deletedPairs.isEmpty())

        composeTestRule.onNodeWithText("حذف").performClick()
        assertEquals(listOf("file-1" to "v1"), deletedPairs)
    }
}
