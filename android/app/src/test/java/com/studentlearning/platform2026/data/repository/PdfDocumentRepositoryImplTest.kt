package com.studentlearning.platform2026.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.download.DownloadExecutionEngine
import com.studentlearning.platform2026.data.download.DownloadStreamFetcher
import com.studentlearning.platform2026.data.download.DownloadStreamResult
import com.studentlearning.platform2026.data.download.DownloadTaskScheduler
import com.studentlearning.platform2026.data.download.LocalPdfStorageManager
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.local.ReadingProgressDataSource
import com.studentlearning.platform2026.data.pdf.PdfContentFetcher
import com.studentlearning.platform2026.data.pdf.PdfDocumentSessionFactory
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.data.pdf.PreparedPdfFile
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.fakes.FakeOfflineDownloadsRepository
import com.studentlearning.platform2026.fakes.FakePdfDocumentSession
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PdfDocumentRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    @Test
    fun `deletes ephemeral temp file when session opening fails or throws cancellation`() = runTest {
        val tempFile1 = File(tempFolder.root, "temp_corrupt.pdf").apply { writeText("%PDF-1.4") }
        val tempFile2 = File(tempFolder.root, "temp_cancel.pdf").apply { writeText("%PDF-1.4") }

        val sourceDataSource = object : PdfFileSourceDataSource {
            override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
            override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                PdfSourceDescriptor.AuthorizedRemoteUrl(
                    url = "https://example.invalid/$fileId.pdf",
                    cacheKey = fileId
                )
        }

        var nextTempFile = tempFile1
        val fetcher = object : PdfContentFetcher {
            override suspend fun prepareReadableFile(source: PdfSourceDescriptor, fileId: String) =
                PreparedPdfFile(file = nextTempFile, deleteOnClose = true)
        }

        var failureToThrow: Throwable = PdfLoadException(PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE)
        val factory = object : PdfDocumentSessionFactory {
            override suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession {
                throw failureToThrow
            }
        }

        val repo = PdfDocumentRepositoryImpl(sourceDataSource, fetcher, factory, testDispatchers)

        val errResult = repo.openDocument("file-1", "v1")
        assertTrue(errResult is AppResult.Error)
        assertFalse(tempFile1.exists())

        nextTempFile = tempFile2
        failureToThrow = CancellationException("Aborted during open")
        try {
            repo.openDocument("file-2", "v1")
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            assertFalse(tempFile2.exists())
        }
    }

    @Test
    fun `preserves non-ephemeral local file when opening fails and returns session on success`() = runTest {
        val persistentLocalFile = File(tempFolder.root, "local_book.pdf").apply { writeText("%PDF-1.7") }

        val sourceDataSource = object : PdfFileSourceDataSource {
            override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
            override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                PdfSourceDescriptor.LocalFilePath(
                    absolutePath = persistentLocalFile.absolutePath,
                    deleteOnSessionClose = false
                )
        }
        val fetcher = object : PdfContentFetcher {
            override suspend fun prepareReadableFile(source: PdfSourceDescriptor, fileId: String) =
                PreparedPdfFile(file = persistentLocalFile, deleteOnClose = false)
        }

        var shouldFail = true
        val expectedSession = FakePdfDocumentSession(fileId = "local-1", pageCount = 5)
        val factory = object : PdfDocumentSessionFactory {
            override suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession {
                if (shouldFail) throw IOException("Transient read error")
                return expectedSession
            }
        }

        val repo = PdfDocumentRepositoryImpl(sourceDataSource, fetcher, factory, testDispatchers)

        val failed = repo.openDocument("local-1", "v1")
        assertTrue(failed is AppResult.Error)
        assertTrue(persistentLocalFile.exists())

        shouldFail = false
        val succeeded = repo.openDocument("local-1", "v1")
        assertTrue(succeeded is AppResult.Success)
        assertEquals(expectedSession, (succeeded as AppResult.Success).data)
    }

    @Test
    fun `opens verified local downloaded version offline without calling remote source`() = runTest {
        val localFileV1 = File(tempFolder.root, "offline_v1.pdf").apply { writeText("%PDF-1.4\nv1") }
        var remoteResolveCalls = 0
        val remoteSource = object : PdfFileSourceDataSource {
            override fun getBrokerStatus() = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
            override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?): PdfSourceDescriptor {
                remoteResolveCalls++
                throw PdfLoadException(PdfFailureReason.NETWORK_UNAVAILABLE, "Offline")
            }
        }
        var capturedSource: PdfSourceDescriptor? = null
        val fetcher = object : PdfContentFetcher {
            override suspend fun prepareReadableFile(source: PdfSourceDescriptor, fileId: String): PreparedPdfFile {
                capturedSource = source
                return PreparedPdfFile(file = localFileV1, deleteOnClose = false)
            }
        }
        val factory = object : PdfDocumentSessionFactory {
            override suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession {
                return FakePdfDocumentSession(fileId = fileId, pageCount = 12)
            }
        }

        val offlineRepo = FakeOfflineDownloadsRepository().apply {
            putSavedVersion(
                LocalDownloadedFileVersion(
                    fileId = "doc-offline",
                    versionId = "v1",
                    versionNumber = 1,
                    versionLabel = "v1",
                    fileTitle = "كتاب الفيزياء",
                    sectionId = "sec-1",
                    localFilePath = localFileV1.absolutePath,
                    fileSizeBytes = localFileV1.length(),
                    checksumSha256 = "abc",
                    downloadedAtEpochMillis = 1_700_000_000_000L
                )
            )
        }

        val repo = PdfDocumentRepositoryImpl(
            sourceDataSource = remoteSource,
            contentFetcher = fetcher,
            sessionFactory = factory,
            dispatcherProvider = testDispatchers,
            offlineDownloadsRepository = offlineRepo
        )

        // Opening saved v1 succeeds offline without calling remoteSource
        val offlineResult = repo.openDocument("doc-offline", "v1")
        assertTrue(offlineResult is AppResult.Success)
        assertEquals(0, remoteResolveCalls)
        assertTrue(capturedSource is PdfSourceDescriptor.LocalFilePath)
        assertFalse((capturedSource as PdfSourceDescriptor.LocalFilePath).deleteOnSessionClose)

        // Asking for newer un-downloaded v2 does NOT silently open v1; falls back to remoteSource
        val newerResult = repo.openDocument("doc-offline", "v2")
        assertTrue(newerResult is AppResult.Error)
        assertEquals(1, remoteResolveCalls)
    }

    @Test
    fun `preserves existing reading progress when opening local downloaded copy`() = runTest {
        val prefsFile = File(tempFolder.newFolder(), "progress.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { prefsFile }
        )
        val progressRepo = ReadingProgressRepositoryImpl(
            localDataSource = ReadingProgressDataSource(dataStore),
            dispatcherProvider = testDispatchers
        )
        progressRepo.saveLastPage(fileId = "doc-prog", pageNumber = 18, totalPages = 40)

        val localFile = File(tempFolder.root, "offline_prog.pdf").apply { writeText("%PDF-1.4") }
        val offlineRepo = FakeOfflineDownloadsRepository().apply {
            putSavedVersion(
                LocalDownloadedFileVersion(
                    fileId = "doc-prog",
                    versionId = "v1",
                    fileTitle = "ملف مع تقدم قراءة",
                    localFilePath = localFile.absolutePath,
                    fileSizeBytes = localFile.length(),
                    checksumSha256 = "sha",
                    downloadedAtEpochMillis = 1_700_000_000_000L
                )
            )
        }
        val docRepo = PdfDocumentRepositoryImpl(
            sourceDataSource = object : PdfFileSourceDataSource {
                override fun getBrokerStatus() = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
                override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?): PdfSourceDescriptor =
                    error("Should open offline")
            },
            contentFetcher = object : PdfContentFetcher {
                override suspend fun prepareReadableFile(source: PdfSourceDescriptor, fileId: String) =
                    PreparedPdfFile(file = localFile, deleteOnClose = false)
            },
            sessionFactory = object : PdfDocumentSessionFactory {
                override suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile) =
                    FakePdfDocumentSession(fileId = fileId, pageCount = 40)
            },
            dispatcherProvider = testDispatchers,
            offlineDownloadsRepository = offlineRepo
        )

        val opened = docRepo.openDocument("doc-prog", "v1")
        assertTrue(opened is AppResult.Success)
        assertEquals(18, progressRepo.getProgress("doc-prog")?.lastPageNumber)
    }

    @Test
    fun `opens both locally stored v1 and v2 separately with exact version bytes via real OfflineDownloadsRepository`() =
        runTest {
            val v1Bytes = "%PDF-1.4\nfirst-version-body".toByteArray()
            val v2Bytes = "%PDF-1.7\nsecond-version-body".toByteArray()

            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { File(tempFolder.newFolder(), "multi_ver.preferences_pb") }
            )
            val dataSource = OfflineDownloadsDataSource(dataStore)
            val storageManager = LocalPdfStorageManager(tempFolder.newFolder("multi_ver_pdfs"))
            val engine = DownloadExecutionEngine(
                sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                        PdfSourceDescriptor.AuthorizedRemoteUrl(
                            url = "https://example.invalid/$fileId/${activeVersionId.orEmpty()}",
                            cacheKey = "$fileId/$activeVersionId"
                        )
                },
                streamFetcher = object : DownloadStreamFetcher {
                    override suspend fun downloadToFile(
                        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                        destinationFile: File,
                        onContentLengthKnown: suspend (Long) -> Unit,
                        onProgress: suspend (Long, Long?) -> Unit
                    ): DownloadStreamResult {
                        val payload = if (source.url.endsWith("v1")) v1Bytes else v2Bytes
                        destinationFile.writeBytes(payload)
                        return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
                    }
                },
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = testDispatchers
            )
            val scheduler = object : DownloadTaskScheduler {
                private val state = MutableStateFlow<Map<Pair<String, String>, DownloadTaskState>>(emptyMap())
                override fun observeTasks(): Flow<Map<Pair<String, String>, DownloadTaskState>> = state
                override suspend fun getTaskState(fileId: String, versionId: String): DownloadTaskState? = null
                override suspend fun enqueue(
                    request: OfflineDownloadRequest,
                    replaceExisting: Boolean
                ): DownloadTaskState {
                    val saved = engine.executeDownload(request)
                    return DownloadTaskState(
                        fileId = request.fileId,
                        versionId = request.versionId,
                        status = DownloadStatus.COMPLETED,
                        bytesDownloaded = saved.fileSizeBytes,
                        totalBytes = saved.fileSizeBytes,
                        progressPercent = 100
                    )
                }

                override suspend fun cancel(fileId: String, versionId: String) = Unit
            }
            val authRepo = FakeAuthRepository(
                AdminSession(
                    isSignedIn = true,
                    uid = "student-1",
                    email = "s@example.com",
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val offlineRepo = OfflineDownloadsRepositoryImpl(
                authRepository = authRepo,
                downloadsDataSource = dataSource,
                storageManager = storageManager,
                taskScheduler = scheduler,
                dispatcherProvider = testDispatchers
            )

            // Download both v1 and v2 of the same Arabic file
            offlineRepo.startDownload(
                StudyFileMetadata(
                    id = "chem-file",
                    sectionId = "sec-chem",
                    title = "ملخص الكيمياء",
                    description = "",
                    order = 1,
                    isPublished = true,
                    archiveStatus = ArchiveStatus.UNARCHIVED,
                    activeVersionId = "v1",
                    activeVersionNumber = 1,
                    activeVersionLabel = "v1"
                )
            )
            offlineRepo.startDownload(
                StudyFileMetadata(
                    id = "chem-file",
                    sectionId = "sec-chem",
                    title = "ملخص الكيمياء",
                    description = "",
                    order = 1,
                    isPublished = true,
                    archiveStatus = ArchiveStatus.UNARCHIVED,
                    activeVersionId = "v2",
                    activeVersionNumber = 2,
                    activeVersionLabel = "v2"
                )
            )

            val openedFiles = mutableMapOf<String, File>()
            val docRepo = PdfDocumentRepositoryImpl(
                sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?): PdfSourceDescriptor =
                        error("Must open both v1 and v2 from verified offline storage without network")
                },
                contentFetcher = object : PdfContentFetcher {
                    override suspend fun prepareReadableFile(source: PdfSourceDescriptor, fileId: String): PreparedPdfFile {
                        val local = source as PdfSourceDescriptor.LocalFilePath
                        return PreparedPdfFile(
                            file = File(local.absolutePath),
                            deleteOnClose = local.deleteOnSessionClose
                        )
                    }
                },
                sessionFactory = object : PdfDocumentSessionFactory {
                    override suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession {
                        openedFiles[preparedFile.file.absolutePath] = preparedFile.file
                        assertFalse(preparedFile.deleteOnClose)
                        return FakePdfDocumentSession(fileId = fileId, pageCount = 5)
                    }
                },
                dispatcherProvider = testDispatchers,
                offlineDownloadsRepository = offlineRepo
            )

            val openV1 = docRepo.openDocument("chem-file", "v1")
            val openV2 = docRepo.openDocument("chem-file", "v2")
            assertTrue(openV1 is AppResult.Success)
            assertTrue(openV2 is AppResult.Success)
            assertEquals(2, openedFiles.size)

            val v1Record = offlineRepo.getVerifiedLocalVersion("chem-file", "v1")!!
            val v2Record = offlineRepo.getVerifiedLocalVersion("chem-file", "v2")!!
            assertTrue(openedFiles[v1Record.localFilePath]!!.readBytes().contentEquals(v1Bytes))
            assertTrue(openedFiles[v2Record.localFilePath]!!.readBytes().contentEquals(v2Bytes))
        }
}
