package com.studentlearning.platform2026.presentation.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.download.DownloadExecutionEngine
import com.studentlearning.platform2026.data.download.DownloadStreamFetcher
import com.studentlearning.platform2026.data.download.DownloadStreamResult
import com.studentlearning.platform2026.data.download.DownloadTaskScheduler
import com.studentlearning.platform2026.data.download.LocalPdfStorageManager
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.data.repository.OfflineDownloadsRepositoryImpl
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.fakes.FakeCatalogRepository
import com.studentlearning.platform2026.fakes.FakeOfflineDownloadsRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SectionFilesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `transitions to Empty when section has no published files`() = runTest {
        val repository = FakeCatalogRepository(
            studyFilesResult = { AppResult.Success(emptyList()) }
        )

        val viewModel = SectionFilesViewModel("sec-1", repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SectionFilesUiState.Empty)
    }

    @Test
    fun `loads published study files and retries cleanly after failure`() = runTest {
        var shouldFail = true
        val files = listOf(
            StudyFileMetadata(
                id = "file-1",
                sectionId = "sec-1",
                title = "ملخص الفصل الأول",
                description = "نسخة منقحة",
                order = 1,
                isPublished = true,
                isArchived = false,
                activeVersionId = "v1"
            )
        )
        val repository = FakeCatalogRepository(
            studyFilesResult = {
                if (shouldFail) {
                    AppResult.Error(
                        message = "Unavailable",
                        cause = CatalogException(CatalogFailureReason.NETWORK_OR_UNAVAILABLE)
                    )
                } else {
                    AppResult.Success(files)
                }
            }
        )

        val viewModel = SectionFilesViewModel("sec-1", repository)
        advanceUntilIdle()

        assertEquals(
            CatalogFailureReason.NETWORK_OR_UNAVAILABLE,
            (viewModel.uiState.value as SectionFilesUiState.Error).reason
        )

        shouldFail = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(files, (viewModel.uiState.value as SectionFilesUiState.Content).files)
    }

    @Test
    fun `guest attempting download triggers sign in prompt without starting download`() = runTest {
        val file = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "ملخص الفصل الأول",
            order = 1,
            isPublished = true,
            isArchived = false,
            activeVersionId = "v1"
        )
        val authRepo = FakeAuthRepository(initialSession = AdminSession()) // GUEST
        val downloadsRepo = FakeOfflineDownloadsRepository(authRepo)
        val catalogRepo = FakeCatalogRepository(studyFilesResult = { AppResult.Success(listOf(file)) })

        val viewModel = SectionFilesViewModel(
            sectionId = "sec-1",
            catalogRepository = catalogRepo,
            authRepository = authRepo,
            offlineDownloadsRepository = downloadsRepo
        )
        advanceUntilIdle()

        val initial = viewModel.uiState.value as SectionFilesUiState.Content
        assertFalse(initial.canDownloadOfflineFiles)

        viewModel.startDownload(file)
        advanceUntilIdle()

        val prompted = viewModel.uiState.value as SectionFilesUiState.Content
        assertEquals("ملخص الفصل الأول", prompted.guestDownloadPromptFileTitle)
        assertEquals(0, downloadsRepo.startDownloadCalls)

        viewModel.dismissGuestDownloadPrompt()
        advanceUntilIdle()
        assertNull((viewModel.uiState.value as SectionFilesUiState.Content).guestDownloadPromptFileTitle)
    }

    @Test
    fun `student tracks download progress cancel retry and preserves older offline version`() = runTest {
        val fileV2 = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "المعادلات التفاضلية",
            order = 1,
            isPublished = true,
            isArchived = false,
            activeVersionId = "v2",
            activeVersionNumber = 2,
            activeVersionLabel = "v2"
        )
        val authRepo = FakeAuthRepository(
            initialSession = AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val downloadsRepo = FakeOfflineDownloadsRepository(authRepo).apply {
            putSavedVersion(
                LocalDownloadedFileVersion(
                    fileId = "file-1",
                    versionId = "v1",
                    versionNumber = 1,
                    versionLabel = "v1",
                    fileTitle = "المعادلات التفاضلية",
                    sectionId = "sec-1",
                    localFilePath = "/private/v1.pdf",
                    fileSizeBytes = 2048L,
                    checksumSha256 = "sha-v1",
                    downloadedAtEpochMillis = 1_700_000_000_000L
                )
            )
        }
        val catalogRepo = FakeCatalogRepository(studyFilesResult = { AppResult.Success(listOf(fileV2)) })

        val viewModel = SectionFilesViewModel(
            sectionId = "sec-1",
            catalogRepository = catalogRepo,
            authRepository = authRepo,
            offlineDownloadsRepository = downloadsRepo
        )
        advanceUntilIdle()

        val beforeStart = (viewModel.uiState.value as SectionFilesUiState.Content).offlineStates["file-1"]!!
        assertTrue(beforeStart.hasNewerOnlineVersionWhileHoldingOlderCopy)
        assertEquals("v1", beforeStart.olderSavedVersions.first().versionId)

        viewModel.startDownload(fileV2)
        advanceUntilIdle()
        assertEquals(1, downloadsRepo.startDownloadCalls)

        downloadsRepo.setTaskState(
            DownloadTaskState(
                fileId = "file-1",
                versionId = "v2",
                status = DownloadStatus.DOWNLOADING,
                progressPercent = 45
            )
        )
        advanceUntilIdle()
        assertEquals(
            45,
            (viewModel.uiState.value as SectionFilesUiState.Content)
                .offlineStates["file-1"]?.activeVersionDownload?.progressPercent
        )

        viewModel.cancelDownload(fileV2)
        advanceUntilIdle()
        assertEquals(1, downloadsRepo.cancelDownloadCalls)
        assertEquals(
            DownloadStatus.CANCELLED,
            (viewModel.uiState.value as SectionFilesUiState.Content)
                .offlineStates["file-1"]?.activeVersionDownload?.status
        )

        viewModel.retryDownload(fileV2)
        advanceUntilIdle()
        assertEquals(1, downloadsRepo.retryDownloadCalls)
    }

    @Test
    fun `catalog network error surfaces already downloaded files for that section offline`() = runTest {
        val authRepo = FakeAuthRepository(initialSession = AdminSession())
        val downloadsRepo = FakeOfflineDownloadsRepository(authRepo).apply {
            putSavedVersion(
                LocalDownloadedFileVersion(
                    fileId = "file-1",
                    versionId = "v1",
                    versionNumber = 1,
                    versionLabel = "v1",
                    fileTitle = "ملخص محفوظ",
                    sectionId = "sec-1",
                    localFilePath = "/private/file1.pdf",
                    fileSizeBytes = 1024L,
                    checksumSha256 = "sha",
                    downloadedAtEpochMillis = 1_700_000_000_000L
                )
            )
        }
        val catalogRepo = FakeCatalogRepository(
            studyFilesResult = {
                AppResult.Error(
                    message = "Offline",
                    cause = CatalogException(CatalogFailureReason.NETWORK_UNAVAILABLE)
                )
            }
        )

        val viewModel = SectionFilesViewModel(
            sectionId = "sec-1",
            catalogRepository = catalogRepo,
            authRepository = authRepo,
            offlineDownloadsRepository = downloadsRepo
        )
        advanceUntilIdle()

        val errState = viewModel.uiState.value as SectionFilesUiState.Error
        assertEquals(CatalogFailureReason.NETWORK_UNAVAILABLE, errState.reason)
        assertEquals(1, errState.offlineSavedFiles.size)
        assertEquals("file-1", errState.offlineSavedFiles.first().fileId)
    }

    @Test
    fun `uiState reflects latest actual state across cancel restart and retry with real repository`() =
        runTest {
            val file = StudyFileMetadata(
                id = "ui-race-file",
                sectionId = "sec-1",
                title = "ملف تزامن الواجهة",
                order = 1,
                isPublished = true,
                isArchived = false,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "v1"
            )
            val authRepo = FakeAuthRepository(
                initialSession = AdminSession(
                    isSignedIn = true,
                    uid = "student-1",
                    email = "student@example.com",
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val dispatchers = object : DispatcherProvider {
                override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
                override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
                override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
            }
            val dataSource = OfflineDownloadsDataSource(
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { File(tempFolder.newFolder(), "ui_race.preferences_pb") }
                )
            )
            val storageManager = LocalPdfStorageManager(tempFolder.newFolder("ui_race_storage"))
            val taskFlow = MutableStateFlow<Map<Pair<String, String>, DownloadTaskState>>(emptyMap())
            var workSeq = 0
            val delayedScheduler = object : DownloadTaskScheduler {
                override fun observeTasks(): Flow<Map<Pair<String, String>, DownloadTaskState>> = taskFlow
                override suspend fun getTaskState(fileId: String, versionId: String): DownloadTaskState? =
                    taskFlow.value[fileId to versionId]

                override suspend fun enqueue(
                    request: OfflineDownloadRequest,
                    replaceExisting: Boolean
                ): DownloadTaskState {
                    workSeq++
                    return DownloadTaskState(
                        fileId = request.fileId,
                        versionId = request.versionId,
                        versionNumber = request.versionNumber,
                        versionLabel = request.versionLabel,
                        status = DownloadStatus.QUEUED,
                        taskId = "ui-work-$workSeq"
                    )
                }

                override suspend fun cancel(fileId: String, versionId: String) = Unit
            }

            val realDownloadsRepo = OfflineDownloadsRepositoryImpl(
                authRepository = authRepo,
                downloadsDataSource = dataSource,
                storageManager = storageManager,
                taskScheduler = delayedScheduler,
                dispatcherProvider = dispatchers
            )
            val engine = DownloadExecutionEngine(
                sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                        PdfSourceDescriptor.AuthorizedRemoteUrl("https://example.invalid/doc.pdf", "k")
                },
                streamFetcher = object : DownloadStreamFetcher {
                    override suspend fun downloadToFile(
                        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                        destinationFile: File,
                        onContentLengthKnown: suspend (Long) -> Unit,
                        onProgress: suspend (Long, Long?) -> Unit
                    ): DownloadStreamResult {
                        val payload = "%PDF-1.4\nui-latest-state".toByteArray()
                        destinationFile.writeBytes(payload)
                        return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
                    }
                },
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = dispatchers
            )

            val viewModel = SectionFilesViewModel(
                sectionId = "sec-1",
                catalogRepository = FakeCatalogRepository(studyFilesResult = { AppResult.Success(listOf(file)) }),
                authRepository = authRepo,
                offlineDownloadsRepository = realDownloadsRepo
            )
            advanceUntilIdle()

            // 1) Simulate running download -> cancel -> UI immediately reflects CANCELLED
            val key = file.id to "v1"
            taskFlow.value = mapOf(
                key to DownloadTaskState(
                    fileId = file.id,
                    versionId = "v1",
                    status = DownloadStatus.DOWNLOADING,
                    progressPercent = 30,
                    taskId = "ui-work-0"
                )
            )
            advanceUntilIdle()

            viewModel.cancelDownload(file)
            advanceUntilIdle()
            val afterCancelState = (viewModel.uiState.value as SectionFilesUiState.Content)
                .offlineStates[file.id]?.activeVersionDownload
            assertEquals(DownloadStatus.CANCELLED, afterCancelState?.status)

            // 2) Immediate restart -> UI reflects QUEUED (not stuck on CANCELLED or old RUNNING)
            viewModel.startDownload(file)
            advanceUntilIdle()
            val afterRestartState = (viewModel.uiState.value as SectionFilesUiState.Content)
                .offlineStates[file.id]?.activeVersionDownload
            assertEquals(DownloadStatus.QUEUED, afterRestartState?.status)

            // 3) Task ui-work-1 fails -> retryDownload -> UI reflects QUEUED immediately even before Flow catches up
            taskFlow.value = mapOf(
                key to DownloadTaskState(
                    fileId = file.id,
                    versionId = "v1",
                    status = DownloadStatus.FAILED,
                    failureReason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                    taskId = "ui-work-1"
                )
            )
            advanceUntilIdle()
            assertEquals(
                DownloadStatus.FAILED,
                (viewModel.uiState.value as SectionFilesUiState.Content)
                    .offlineStates[file.id]?.activeVersionDownload?.status
            )

            viewModel.retryDownload(file)
            advanceUntilIdle()
            val afterRetryUi = (viewModel.uiState.value as SectionFilesUiState.Content)
                .offlineStates[file.id]?.activeVersionDownload
            assertEquals(DownloadStatus.QUEUED, afterRetryUi?.status)
            assertNull(afterRetryUi?.failureReason)

            // 4) New task completes -> UI reflects COMPLETED
            val saved = engine.executeDownload(OfflineDownloadRequest(file.id, "v1", 1, "v1", file.title))
            taskFlow.value = mapOf(
                key to DownloadTaskState(
                    fileId = file.id,
                    versionId = "v1",
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = saved.fileSizeBytes,
                    totalBytes = saved.fileSizeBytes,
                    progressPercent = 100,
                    taskId = "ui-work-2"
                )
            )
            advanceUntilIdle()
            val finalUi = (viewModel.uiState.value as SectionFilesUiState.Content).offlineStates[file.id]!!
            assertEquals(DownloadStatus.COMPLETED, finalUi.activeVersionDownload?.status)
            assertTrue(finalUi.hasActiveVersionLocalCopy)
        }
}
