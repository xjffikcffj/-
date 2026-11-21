package com.studentlearning.platform2026.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.download.DownloadExecutionEngine
import com.studentlearning.platform2026.data.download.DownloadStreamFetcher
import com.studentlearning.platform2026.data.download.DownloadStreamResult
import com.studentlearning.platform2026.data.download.DownloadTaskScheduler
import com.studentlearning.platform2026.data.download.HttpDownloadStreamFetcher
import com.studentlearning.platform2026.data.download.LocalPdfStorageManager
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.pdf.BrokeredPdfFileSourceDataSource
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.fakes.FakeAdminCatalogRepository
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloadsRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    private fun studentSession(uid: String = "student-1", email: String = "student1@example.com") =
        AdminSession(
            isSignedIn = true,
            uid = uid,
            email = email,
            role = AdminRole.NONE,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )

    private fun sampleFile(
        id: String = "file-1",
        sectionId: String = "sec-1",
        title: String = "ملخص الفصل الأول",
        versionId: String = "v1",
        versionNumber: Int = 1,
        versionLabel: String = "v1"
    ) = StudyFileMetadata(
        id = id,
        sectionId = sectionId,
        title = title,
        description = "نسخة دراسية",
        order = 1,
        isPublished = true,
        archiveStatus = ArchiveStatus.UNARCHIVED,
        activeVersionId = versionId,
        activeVersionNumber = versionNumber,
        activeVersionLabel = versionLabel
    )

    private class InMemoryDownloadTaskScheduler(
        private val scope: CoroutineScope,
        private val executionEngine: DownloadExecutionEngine
    ) : DownloadTaskScheduler {

        private val tasks = MutableStateFlow<Map<Pair<String, String>, DownloadTaskState>>(emptyMap())
        private val activeJobs = mutableMapOf<Pair<String, String>, Job>()
        var enqueueCalls = 0
            private set

        override fun observeTasks(): Flow<Map<Pair<String, String>, DownloadTaskState>> = tasks

        override suspend fun getTaskState(fileId: String, versionId: String): DownloadTaskState? =
            tasks.value[fileId to versionId]

        override suspend fun enqueue(
            request: OfflineDownloadRequest,
            replaceExisting: Boolean
        ): DownloadTaskState {
            val key = request.fileId to request.versionId
            val existing = tasks.value[key]
            if (!replaceExisting && existing != null && existing.status.isInProgress) {
                return existing
            }
            if (replaceExisting) {
                activeJobs.remove(key)?.cancel()
            }
            enqueueCalls++

            val queued = DownloadTaskState(
                fileId = request.fileId,
                versionId = request.versionId,
                versionNumber = request.versionNumber,
                versionLabel = request.versionLabel,
                status = DownloadStatus.QUEUED,
                totalBytes = request.expectedFileSizeBytes
            )
            tasks.update { it + (key to queued) }

            val job = scope.launch {
                tasks.update {
                    it + (
                        key to queued.copy(
                            status = DownloadStatus.DOWNLOADING
                        )
                    )
                }
                try {
                    val saved = executionEngine.executeDownload(request) { downloaded, total, percent ->
                        tasks.update { current ->
                            current + (
                                key to DownloadTaskState(
                                    fileId = request.fileId,
                                    versionId = request.versionId,
                                    versionNumber = request.versionNumber,
                                    versionLabel = request.versionLabel,
                                    status = DownloadStatus.DOWNLOADING,
                                    bytesDownloaded = downloaded,
                                    totalBytes = total,
                                    progressPercent = percent
                                )
                            )
                        }
                    }
                    tasks.update { current ->
                        current + (
                            key to DownloadTaskState(
                                fileId = request.fileId,
                                versionId = request.versionId,
                                versionNumber = saved.versionNumber,
                                versionLabel = saved.versionLabel,
                                status = DownloadStatus.COMPLETED,
                                bytesDownloaded = saved.fileSizeBytes,
                                totalBytes = saved.fileSizeBytes,
                                progressPercent = 100
                            )
                        )
                    }
                } catch (cancelled: CancellationException) {
                    tasks.update { current ->
                        current + (
                            key to DownloadTaskState(
                                fileId = request.fileId,
                                versionId = request.versionId,
                                versionNumber = request.versionNumber,
                                versionLabel = request.versionLabel,
                                status = DownloadStatus.CANCELLED
                            )
                        )
                    }
                    throw cancelled
                } catch (err: DownloadException) {
                    tasks.update { current ->
                        current + (
                            key to DownloadTaskState(
                                fileId = request.fileId,
                                versionId = request.versionId,
                                versionNumber = request.versionNumber,
                                versionLabel = request.versionLabel,
                                status = DownloadStatus.FAILED,
                                failureReason = err.reason
                            )
                        )
                    }
                }
            }
            activeJobs[key] = job
            return queued
        }

        override suspend fun cancel(fileId: String, versionId: String) {
            val key = fileId to versionId
            activeJobs.remove(key)?.cancel()
            tasks.update { current ->
                current + (
                    key to DownloadTaskState(
                        fileId = fileId,
                        versionId = versionId,
                        status = DownloadStatus.CANCELLED
                    )
                )
            }
        }
    }

    private data class Harness(
        val authRepository: FakeAuthRepository,
        val storageRoot: File,
        val storageManager: LocalPdfStorageManager,
        val dataSource: OfflineDownloadsDataSource,
        val scheduler: InMemoryDownloadTaskScheduler,
        val repository: OfflineDownloadsRepositoryImpl
    )

    private fun createHarness(
        scope: TestScope,
        initialSession: AdminSession = studentSession(),
        sourceDataSource: PdfFileSourceDataSource = object : PdfFileSourceDataSource {
            override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
            override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                PdfSourceDescriptor.AuthorizedRemoteUrl(
                    url = "https://example.invalid/$fileId/${activeVersionId.orEmpty()}.pdf",
                    cacheKey = "${fileId}_${activeVersionId.orEmpty()}"
                )
        },
        streamFetcher: DownloadStreamFetcher,
        usableSpaceProvider: (File) -> Long = { 64L * 1024L * 1024L },
        prefsFile: File = File(tempFolder.newFolder(), "offline_downloads.preferences_pb"),
        storageRoot: File = tempFolder.newFolder("offline_storage")
    ): Harness {
        val authRepository = FakeAuthRepository(initialSession = initialSession)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { prefsFile }
        )
        val dataSource = OfflineDownloadsDataSource(dataStore)
        val storageManager = LocalPdfStorageManager(
            storageRoot = storageRoot,
            usableSpaceProvider = usableSpaceProvider
        )
        val engine = DownloadExecutionEngine(
            sourceDataSource = sourceDataSource,
            streamFetcher = streamFetcher,
            storageManager = storageManager,
            downloadsDataSource = dataSource,
            dispatcherProvider = testDispatchers
        )
        val scheduler = InMemoryDownloadTaskScheduler(scope.backgroundScope, engine)
        val repository = OfflineDownloadsRepositoryImpl(
            authRepository = authRepository,
            downloadsDataSource = dataSource,
            storageManager = storageManager,
            taskScheduler = scheduler,
            dispatcherProvider = testDispatchers
        )
        return Harness(authRepository, storageRoot, storageManager, dataSource, scheduler, repository)
    }

    @Test
    fun `guest is blocked from starting download even on direct repository call`() = runTest {
        val fetcher = object : DownloadStreamFetcher {
            override suspend fun downloadToFile(
                source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                destinationFile: File,
                onContentLengthKnown: suspend (Long) -> Unit,
                onProgress: suspend (Long, Long?) -> Unit
            ): DownloadStreamResult = error("Should never execute for guest")
        }
        val harness = createHarness(
            scope = this,
            initialSession = AdminSession(), // GUEST
            streamFetcher = fetcher
        )

        val result = harness.repository.startDownload(sampleFile())
        advanceUntilIdle()

        assertTrue(result is AppResult.Error)
        val err = result as AppResult.Error
        assertEquals(
            DownloadFailureReason.UNAUTHENTICATED_GUEST,
            (err.cause as DownloadException).reason
        )
        assertEquals(0, harness.scheduler.enqueueCalls)
        assertTrue(harness.dataSource.getAllRecords().isEmpty())
    }

    @Test
    fun `signed in student downloads PDF from loopback server with sha256 verification and cleans temp`() =
        runTest {
            val pdfPayload = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n" +
                "stream_content_for_stage7_test").toByteArray(Charsets.UTF_8)
            val expectedSha = MessageDigest.getInstance("SHA-256")
                .digest(pdfPayload)
                .joinToString("") { "%02x".format(it) }

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/book.pdf") { exchange ->
                exchange.responseHeaders.add("X-Content-Sha256", expectedSha)
                exchange.sendResponseHeaders(200, pdfPayload.size.toLong())
                exchange.responseBody.use { it.write(pdfPayload) }
            }
            server.start()
            try {
                val port = server.address.port
                val harness = createHarness(
                    scope = this,
                    sourceDataSource = object : PdfFileSourceDataSource {
                        override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                        override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                            PdfSourceDescriptor.AuthorizedRemoteUrl(
                                url = "http://127.0.0.1:$port/book.pdf",
                                cacheKey = "$fileId/$activeVersionId"
                            )
                    },
                    streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true)
                )

                val result = harness.repository.startDownload(sampleFile())
                assertTrue(result is AppResult.Success)
                advanceUntilIdle()

                val saved = harness.repository.getVerifiedLocalVersion("file-1", "v1")
                assertNotNull(saved)
                assertEquals(pdfPayload.size.toLong(), saved?.fileSizeBytes)
                assertEquals(expectedSha, saved?.checksumSha256)
                assertTrue(File(saved!!.localFilePath).exists())
                assertTrue(saved.localFilePath.startsWith(harness.storageRoot.absolutePath))
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())

                val state = harness.repository.observeFileOfflineState("file-1", "v1").first()
                assertEquals(DownloadStatus.COMPLETED, state.activeVersionDownload?.status)
                assertEquals(100, state.activeVersionDownload?.progressPercent)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `cancelling mid download cleans part temp file and preserves already completed versions`() =
        runTest {
            val startedSignal = CompletableDeferred<Unit>()
            val holdSignal = CompletableDeferred<Unit>()
            val v1Payload = "%PDF-1.4\nversion-1-valid".toByteArray()

            val fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    return if (source.url.contains("v1")) {
                        destinationFile.writeBytes(v1Payload)
                        DownloadStreamResult(v1Payload.size.toLong(), v1Payload.size.toLong(), null)
                    } else {
                        destinationFile.writeBytes("%PDF-partial".toByteArray())
                        onProgress(12L, 1000L)
                        startedSignal.complete(Unit)
                        holdSignal.await()
                        DownloadStreamResult(12L, 1000L, null)
                    }
                }
            }

            val harness = createHarness(scope = this, streamFetcher = fetcher)

            // Download v1 completely first
            harness.repository.startDownload(sampleFile(versionId = "v1", versionNumber = 1, versionLabel = "v1"))
            advanceUntilIdle()
            val v1Saved = harness.repository.getVerifiedLocalVersion("file-1", "v1")
            assertNotNull(v1Saved)

            // Start v2, wait until mid-download, then cancel
            harness.repository.startDownload(sampleFile(versionId = "v2", versionNumber = 2, versionLabel = "v2"))
            startedSignal.await()
            assertTrue(harness.storageManager.listAllTempFiles().isNotEmpty())

            harness.repository.cancelDownload("file-1", "v2")
            advanceUntilIdle()

            assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            assertNull(harness.repository.getVerifiedLocalVersion("file-1", "v2"))
            assertNotNull(harness.repository.getVerifiedLocalVersion("file-1", "v1"))
            assertTrue(File(v1Saved!!.localFilePath).exists())
        }

    @Test
    fun `network failure and server error mark failed clean temp files and retryDownload succeeds`() =
        runTest {
            var failFirstAttempt = true
            val validPayload = "%PDF-1.7\nrecovered-content".toByteArray()
            val fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    if (failFirstAttempt) {
                        destinationFile.writeBytes("%PDF-corrupt-partial".toByteArray())
                        throw DownloadException(
                            reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                            message = "Connection reset"
                        )
                    }
                    destinationFile.writeBytes(validPayload)
                    return DownloadStreamResult(
                        bytesWritten = validPayload.size.toLong(),
                        contentLengthHeaderBytes = validPayload.size.toLong(),
                        serverSha256Header = null
                    )
                }
            }

            val harness = createHarness(scope = this, streamFetcher = fetcher)
            val file = sampleFile()

            harness.repository.startDownload(file)
            advanceUntilIdle()

            val failedState = harness.repository.observeFileOfflineState(file.id, file.activeVersionId).first()
            assertEquals(DownloadStatus.FAILED, failedState.activeVersionDownload?.status)
            assertEquals(DownloadFailureReason.NETWORK_UNAVAILABLE, failedState.activeVersionDownload?.failureReason)
            assertNull(harness.repository.getVerifiedLocalVersion(file.id, file.activeVersionId))
            assertTrue(harness.storageManager.listAllTempFiles().isEmpty())

            failFirstAttempt = false
            harness.repository.retryDownload(file)
            advanceUntilIdle()

            val recoveredState = harness.repository.observeFileOfflineState(file.id, file.activeVersionId).first()
            assertEquals(DownloadStatus.COMPLETED, recoveredState.activeVersionDownload?.status)
            assertNotNull(harness.repository.getVerifiedLocalVersion(file.id, file.activeVersionId))
        }

    @Test
    fun `rejects corrupted payload or checksum mismatch and cleans up temp file`() = runTest {
        val corruptBytes = "HTML error page instead of PDF".toByteArray()
        val fetcher = object : DownloadStreamFetcher {
            override suspend fun downloadToFile(
                source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                destinationFile: File,
                onContentLengthKnown: suspend (Long) -> Unit,
                onProgress: suspend (Long, Long?) -> Unit
            ): DownloadStreamResult {
                destinationFile.writeBytes(corruptBytes)
                return DownloadStreamResult(
                    bytesWritten = corruptBytes.size.toLong(),
                    contentLengthHeaderBytes = corruptBytes.size.toLong(),
                    serverSha256Header = null
                )
            }
        }

        val harness = createHarness(scope = this, streamFetcher = fetcher)
        harness.repository.startDownload(sampleFile())
        advanceUntilIdle()

        val state = harness.repository.observeFileOfflineState("file-1", "v1").first()
        assertEquals(DownloadStatus.FAILED, state.activeVersionDownload?.status)
        assertEquals(DownloadFailureReason.INTEGRITY_CHECK_FAILED, state.activeVersionDownload?.failureReason)
        assertNull(harness.repository.getVerifiedLocalVersion("file-1", "v1"))
        assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
    }

    @Test
    fun `insufficient storage and write errors fail explicitly without marking complete`() = runTest {
        val payload = "%PDF-1.4\nsmall".toByteArray()
        val okFetcher = object : DownloadStreamFetcher {
            override suspend fun downloadToFile(
                source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                destinationFile: File,
                onContentLengthKnown: suspend (Long) -> Unit,
                onProgress: suspend (Long, Long?) -> Unit
            ): DownloadStreamResult {
                destinationFile.writeBytes(payload)
                return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
            }
        }

        val lowSpaceHarness = createHarness(
            scope = this,
            streamFetcher = okFetcher,
            usableSpaceProvider = { 1024L } // Below minimum required free buffer
        )
        lowSpaceHarness.repository.startDownload(sampleFile())
        advanceUntilIdle()

        val lowSpaceState = lowSpaceHarness.repository.observeFileOfflineState("file-1", "v1").first()
        assertEquals(DownloadStatus.FAILED, lowSpaceState.activeVersionDownload?.status)
        assertEquals(DownloadFailureReason.INSUFFICIENT_STORAGE, lowSpaceState.activeVersionDownload?.failureReason)
        assertTrue(lowSpaceHarness.storageManager.listAllTempFiles().isEmpty())

        val writeErrorFetcher = object : DownloadStreamFetcher {
            override suspend fun downloadToFile(
                source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                destinationFile: File,
                onContentLengthKnown: suspend (Long) -> Unit,
                onProgress: suspend (Long, Long?) -> Unit
            ): DownloadStreamResult {
                throw DownloadException(
                    reason = DownloadFailureReason.STORAGE_WRITE_ERROR,
                    message = IOException("Read-only filesystem").message
                )
            }
        }
        val writeErrHarness = createHarness(scope = this, streamFetcher = writeErrorFetcher)
        writeErrHarness.repository.startDownload(sampleFile())
        advanceUntilIdle()

        val writeErrState = writeErrHarness.repository.observeFileOfflineState("file-1", "v1").first()
        assertEquals(DownloadStatus.FAILED, writeErrState.activeVersionDownload?.status)
        assertEquals(DownloadFailureReason.STORAGE_WRITE_ERROR, writeErrState.activeVersionDownload?.failureReason)
        assertTrue(writeErrHarness.storageManager.listAllTempFiles().isEmpty())
    }

    @Test
    fun `prevents duplicate concurrent and redundant completed downloads for same version`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val payload = "%PDF-1.4\nuniq".toByteArray()
        val fetcher = object : DownloadStreamFetcher {
            override suspend fun downloadToFile(
                source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                destinationFile: File,
                onContentLengthKnown: suspend (Long) -> Unit,
                onProgress: suspend (Long, Long?) -> Unit
            ): DownloadStreamResult {
                gate.await()
                destinationFile.writeBytes(payload)
                return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
            }
        }

        val harness = createHarness(scope = this, streamFetcher = fetcher)
        val file = sampleFile()

        // Duplicate while in progress
        harness.repository.startDownload(file)
        harness.repository.startDownload(file)
        assertEquals(1, harness.scheduler.enqueueCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        // Duplicate after completed
        val thirdCall = harness.repository.startDownload(file)
        assertTrue(thirdCall is AppResult.Success)
        assertEquals(DownloadStatus.COMPLETED, (thirdCall as AppResult.Success).data.status)
        assertEquals(1, harness.scheduler.enqueueCalls)
    }

    @Test
    fun `keeps older downloaded version v1 intact when newer version v2 is published and downloaded`() =
        runTest {
            val v1Bytes = "%PDF-1.4\nversion-one-content".toByteArray()
            val v2Bytes = "%PDF-1.4\nversion-two-updated-content".toByteArray()
            val fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    val bytes = if (source.url.contains("v1")) v1Bytes else v2Bytes
                    destinationFile.writeBytes(bytes)
                    return DownloadStreamResult(bytes.size.toLong(), bytes.size.toLong(), null)
                }
            }

            val harness = createHarness(scope = this, streamFetcher = fetcher)

            val fileV1 = sampleFile(versionId = "v1", versionNumber = 1, versionLabel = "v1")
            harness.repository.startDownload(fileV1)
            advanceUntilIdle()

            // New version v2 published online -> student still holds v1 offline before downloading v2
            val stateWithNewerOnline = harness.repository.observeFileOfflineState("file-1", "v2").first()
            assertNull(stateWithNewerOnline.activeVersionSavedCopy)
            assertEquals(1, stateWithNewerOnline.olderSavedVersions.size)
            assertEquals("v1", stateWithNewerOnline.olderSavedVersions.first().versionId)
            assertTrue(stateWithNewerOnline.hasNewerOnlineVersionWhileHoldingOlderCopy)

            // Download v2 independently -> v1 remains intact on disk and in metadata!
            val fileV2 = sampleFile(versionId = "v2", versionNumber = 2, versionLabel = "v2")
            harness.repository.startDownload(fileV2)
            advanceUntilIdle()

            val v1Local = harness.repository.getVerifiedLocalVersion("file-1", "v1")
            val v2Local = harness.repository.getVerifiedLocalVersion("file-1", "v2")
            assertNotNull(v1Local)
            assertNotNull(v2Local)
            assertTrue(File(v1Local!!.localFilePath).readBytes().contentEquals(v1Bytes))
            assertTrue(File(v2Local!!.localFilePath).readBytes().contentEquals(v2Bytes))

            val allVersionsForFile = harness.repository.getSavedVersionsForFile("file-1")
            assertEquals(2, allVersionsForFile.size)
        }

    @Test
    fun `sign out and account switch do not delete device local downloads and metadata stays uid independent`() =
        runTest {
            val bytes = "%PDF-1.4\ndevice-local-data".toByteArray()
            val fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    destinationFile.writeBytes(bytes)
                    return DownloadStreamResult(bytes.size.toLong(), bytes.size.toLong(), null)
                }
            }
            val prefsFile = File(tempFolder.newFolder(), "device_local.preferences_pb")
            val storageRoot = tempFolder.newFolder("device_storage")

            val harness = createHarness(
                scope = this,
                initialSession = studentSession(uid = "student_A", email = "a@example.com"),
                streamFetcher = fetcher,
                prefsFile = prefsFile,
                storageRoot = storageRoot
            )

            harness.repository.startDownload(sampleFile(id = "f-local", versionId = "v1"))
            advanceUntilIdle()

            // Sign out to guest, then sign in as student_B
            harness.authRepository.signOut()
            harness.authRepository.setSession(studentSession(uid = "student_B", email = "b@example.com"))

            val afterSwitch = harness.repository.getVerifiedLocalVersion("f-local", "v1")
            assertNotNull(afterSwitch)
            assertTrue(File(afterSwitch!!.localFilePath).exists())
            assertFalse(afterSwitch.localFilePath.contains("student_A"))
            assertFalse(afterSwitch.localFilePath.contains("a@example.com"))
        }

    @Test
    fun `files with identical arabic titles never collide on disk or metadata`() = runTest {
        val bytesA = "%PDF-1.4\ncontent-file-alpha".toByteArray()
        val bytesB = "%PDF-1.4\ncontent-file-beta".toByteArray()
        val fetcher = object : DownloadStreamFetcher {
            override suspend fun downloadToFile(
                source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                destinationFile: File,
                onContentLengthKnown: suspend (Long) -> Unit,
                onProgress: suspend (Long, Long?) -> Unit
            ): DownloadStreamResult {
                val payload = if (source.url.contains("math-doc")) bytesA else bytesB
                destinationFile.writeBytes(payload)
                return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
            }
        }

        val harness = createHarness(scope = this, streamFetcher = fetcher)
        val sameTitle = "ملخص الفصل الأول"
        harness.repository.startDownload(
            sampleFile(id = "math-doc", title = sameTitle, versionId = "v1")
        )
        harness.repository.startDownload(
            sampleFile(id = "physics-doc", title = sameTitle, versionId = "v1")
        )
        advanceUntilIdle()

        val savedA = harness.repository.getVerifiedLocalVersion("math-doc", "v1")
        val savedB = harness.repository.getVerifiedLocalVersion("physics-doc", "v1")
        assertNotNull(savedA)
        assertNotNull(savedB)
        assertTrue(savedA!!.localFilePath != savedB!!.localFilePath)
        assertTrue(File(savedA.localFilePath).readBytes().contentEquals(bytesA))
        assertTrue(File(savedB.localFilePath).readBytes().contentEquals(bytesB))
    }

    @Test
    fun `unconfigured Cloudflare R2 broker fails explicitly without fake URLs`() = runTest {
        val brokerDataSource = BrokeredPdfFileSourceDataSource(brokerEndpointBaseUrl = null)
        val fetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = false)

        val harness = createHarness(
            scope = this,
            sourceDataSource = brokerDataSource,
            streamFetcher = fetcher
        )
        harness.repository.startDownload(sampleFile())
        advanceUntilIdle()

        val state = harness.repository.observeFileOfflineState("file-1", "v1").first()
        assertEquals(DownloadStatus.FAILED, state.activeVersionDownload?.status)
        assertEquals(
            DownloadFailureReason.SECURE_BROKER_NOT_CONFIGURED,
            state.activeVersionDownload?.failureReason
        )
        assertNull(harness.repository.getVerifiedLocalVersion("file-1", "v1"))
    }

    @Test
    fun `rejects malformed X-Content-Sha256 header while empty or absent falls back to expectedChecksumSha256`() =
        runTest {
            val pdfPayload = "%PDF-1.4\nchecksum-header-policy-test".toByteArray(Charsets.UTF_8)
            val validSha = MessageDigest.getInstance("SHA-256")
                .digest(pdfPayload)
                .joinToString("") { "%02x".format(it) }
            val wrongSha = "0".repeat(64)

            var headerMode = "malformed"
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/doc.pdf") { exchange ->
                when (headerMode) {
                    "malformed" -> exchange.responseHeaders.add("X-Content-Sha256", "not-a-valid-sha256!")
                    "empty" -> exchange.responseHeaders.add("X-Content-Sha256", "   ")
                    "valid_upper" -> exchange.responseHeaders.add("X-Content-Sha256", validSha.uppercase())
                    "absent" -> Unit
                }
                exchange.sendResponseHeaders(200, pdfPayload.size.toLong())
                exchange.responseBody.use { it.write(pdfPayload) }
            }
            server.start()

            try {
                val port = server.address.port
                val sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                        PdfSourceDescriptor.AuthorizedRemoteUrl(
                            url = "http://127.0.0.1:$port/doc.pdf",
                            cacheKey = "$fileId/$activeVersionId"
                        )
                }

                // 1) Malformed non-blank X-Content-Sha256 -> INTEGRITY_CHECK_FAILED & temp cleaned
                headerMode = "malformed"
                val malformedHarness = createHarness(
                    scope = this,
                    sourceDataSource = sourceDataSource,
                    streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true)
                )
                malformedHarness.repository.startDownload(sampleFile(versionId = "v1"))
                advanceUntilIdle()
                val malformedState = malformedHarness.repository.observeFileOfflineState("file-1", "v1").first()
                assertEquals(DownloadStatus.FAILED, malformedState.activeVersionDownload?.status)
                assertEquals(
                    DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                    malformedState.activeVersionDownload?.failureReason
                )
                assertNull(malformedHarness.repository.getVerifiedLocalVersion("file-1", "v1"))
                assertTrue(malformedHarness.storageManager.listAllTempFiles().isEmpty())

                // 2) Valid uppercase X-Content-Sha256 -> succeeds & normalizes lowercase
                headerMode = "valid_upper"
                val validHarness = createHarness(
                    scope = this,
                    sourceDataSource = sourceDataSource,
                    streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true)
                )
                validHarness.repository.startDownload(sampleFile(versionId = "v2"))
                advanceUntilIdle()
                val savedValid = validHarness.repository.getVerifiedLocalVersion("file-1", "v2")
                assertNotNull(savedValid)
                assertEquals(validSha, savedValid?.checksumSha256)

                // 3) Empty X-Content-Sha256 falls back to expectedChecksumSha256 (mismatch fails, match succeeds)
                headerMode = "empty"
                val emptyFailHarness = createHarness(
                    scope = this,
                    sourceDataSource = sourceDataSource,
                    streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true)
                )
                emptyFailHarness.repository.startDownload(
                    file = sampleFile(versionId = "v3"),
                    expectedChecksumSha256 = wrongSha
                )
                advanceUntilIdle()
                val emptyFailState = emptyFailHarness.repository.observeFileOfflineState("file-1", "v3").first()
                assertEquals(DownloadStatus.FAILED, emptyFailState.activeVersionDownload?.status)
                assertEquals(
                    DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                    emptyFailState.activeVersionDownload?.failureReason
                )

                val emptyOkHarness = createHarness(
                    scope = this,
                    sourceDataSource = sourceDataSource,
                    streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true)
                )
                emptyOkHarness.repository.startDownload(
                    file = sampleFile(versionId = "v4"),
                    expectedChecksumSha256 = validSha
                )
                advanceUntilIdle()
                assertNotNull(emptyOkHarness.repository.getVerifiedLocalVersion("file-1", "v4"))

                // 4) Absent X-Content-Sha256 continues with expectedChecksumSha256 policy
                headerMode = "absent"
                val absentHarness = createHarness(
                    scope = this,
                    sourceDataSource = sourceDataSource,
                    streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true)
                )
                absentHarness.repository.startDownload(
                    file = sampleFile(versionId = "v5"),
                    expectedChecksumSha256 = validSha
                )
                advanceUntilIdle()
                assertNotNull(absentHarness.repository.getVerifiedLocalVersion("file-1", "v5"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `rejects 3xx redirect without following unvalidated redirect target`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect.pdf") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/final.pdf")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/final.pdf") {
            error("Redirect target must not be contacted")
        }
        server.start()
        try {
            val port = server.address.port
            val harness = createHarness(
                scope = this,
                sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                        PdfSourceDescriptor.AuthorizedRemoteUrl(
                            url = "http://127.0.0.1:$port/redirect.pdf",
                            cacheKey = "$fileId/$activeVersionId"
                        )
                },
                streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true)
            )

            harness.repository.startDownload(sampleFile())
            advanceUntilIdle()

            val state = harness.repository.observeFileOfflineState("file-1", "v1").first()
            assertEquals(DownloadStatus.FAILED, state.activeVersionDownload?.status)
            assertEquals(DownloadFailureReason.SERVER_ERROR, state.activeVersionDownload?.failureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `immediate restart after cancel replaces stale cancelling task and retry hides stale FAILED`() =
        runTest {
            val validPayload = "%PDF-1.4\nrace-safe-content".toByteArray()
            val taskFlow = MutableStateFlow<Map<Pair<String, String>, DownloadTaskState>>(emptyMap())
            val storageRoot = tempFolder.newFolder("race_storage")
            val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
            val prefsFile = File(tempFolder.newFolder(), "race.preferences_pb")
            val dataSource = OfflineDownloadsDataSource(
                PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { prefsFile })
            )
            val executionEngine = DownloadExecutionEngine(
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
                        destinationFile.writeBytes(validPayload)
                        return DownloadStreamResult(validPayload.size.toLong(), validPayload.size.toLong(), null)
                    }
                },
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = testDispatchers
            )

            // Scheduler simulating delayed WorkManager Flow transitions where an old taskId remains
            // RUNNING briefly after cancel or FAILED briefly after retry.
            var nextTaskSeq = 0
            var enqueueCalls = 0
            val racerScheduler = object : DownloadTaskScheduler {
                override fun observeTasks(): Flow<Map<Pair<String, String>, DownloadTaskState>> = taskFlow

                override suspend fun getTaskState(fileId: String, versionId: String): DownloadTaskState? =
                    taskFlow.value[fileId to versionId]

                override suspend fun enqueue(
                    request: OfflineDownloadRequest,
                    replaceExisting: Boolean
                ): DownloadTaskState {
                    enqueueCalls++
                    nextTaskSeq++
                    val newId = "work-$nextTaskSeq"
                    return DownloadTaskState(
                        fileId = request.fileId,
                        versionId = request.versionId,
                        status = DownloadStatus.QUEUED,
                        taskId = newId
                    )
                }

                override suspend fun cancel(fileId: String, versionId: String) {
                    // Simulate WorkInfo still lingering as RUNNING for the same workId momentarily.
                }
            }

            val repo = OfflineDownloadsRepositoryImpl(
                authRepository = FakeAuthRepository(studentSession()),
                downloadsDataSource = dataSource,
                storageManager = storageManager,
                taskScheduler = racerScheduler,
                dispatcherProvider = testDispatchers
            )
            val key = "file-1" to "v1"

            // 1) Simulate work-0 RUNNING, cancelDownload, then immediate startDownload
            taskFlow.value = mapOf(
                key to DownloadTaskState(
                    fileId = "file-1",
                    versionId = "v1",
                    status = DownloadStatus.DOWNLOADING,
                    progressPercent = 40,
                    taskId = "work-0"
                )
            )
            repo.cancelDownload("file-1", "v1")
            val cancelledState = repo.observeFileOfflineState("file-1", "v1").first()
            assertEquals(DownloadStatus.CANCELLED, cancelledState.activeVersionDownload?.status)

            val restartResult = repo.startDownload(sampleFile())
            assertTrue(restartResult is AppResult.Success)
            assertEquals(1, enqueueCalls)
            val restartedState = repo.observeFileOfflineState("file-1", "v1").first()
            assertEquals(DownloadStatus.QUEUED, restartedState.activeVersionDownload?.status)

            // 2) Simulate work-1 emitted FAILED, then retryDownload while Flow still holds work-1 FAILED
            taskFlow.value = mapOf(
                key to DownloadTaskState(
                    fileId = "file-1",
                    versionId = "v1",
                    status = DownloadStatus.FAILED,
                    failureReason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                    taskId = "work-1"
                )
            )
            val retryResult = repo.retryDownload(sampleFile())
            assertTrue(retryResult is AppResult.Success)
            assertEquals(2, enqueueCalls)
            val retriedState = repo.observeFileOfflineState("file-1", "v1").first()
            assertEquals(DownloadStatus.QUEUED, retriedState.activeVersionDownload?.status)
            assertNull(retriedState.activeVersionDownload?.failureReason)

            // Once new work-2 completes, offline file is verified and state becomes COMPLETED
            val saved = executionEngine.executeDownload(
                OfflineDownloadRequest("file-1", "v1", 1, "v1", "كتاب")
            )
            taskFlow.value = mapOf(
                key to DownloadTaskState(
                    fileId = "file-1",
                    versionId = "v1",
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = saved.fileSizeBytes,
                    totalBytes = saved.fileSizeBytes,
                    progressPercent = 100,
                    taskId = "work-2"
                )
            )
            val finalState = repo.observeFileOfflineState("file-1", "v1").first()
            assertEquals(DownloadStatus.COMPLETED, finalState.activeVersionDownload?.status)
        }

    @Test
    fun `cancel racing concurrently with NonCancellable commit never deletes completed file or emits CANCELLED`() =
        runTest {
            val payload = "%PDF-1.4\natomic-commit-wins".toByteArray()
            val updateEntered = CompletableDeferred<Unit>()
            val allowUpdateFinish = CompletableDeferred<Unit>()

            val baseDataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { File(tempFolder.newFolder(), "cancel_commit_race.preferences_pb") }
            )
            val interceptingDataStore = object : DataStore<Preferences> by baseDataStore {
                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences
                ): Preferences {
                    updateEntered.complete(Unit)
                    allowUpdateFinish.await()
                    return baseDataStore.updateData(transform)
                }
            }

            val storageRoot = tempFolder.newFolder("cancel_commit_race_storage")
            val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
            val dataSource = OfflineDownloadsDataSource(interceptingDataStore)
            val engine = DownloadExecutionEngine(
                sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                        PdfSourceDescriptor.AuthorizedRemoteUrl(
                            url = "https://example.invalid/$fileId/${activeVersionId.orEmpty()}.pdf",
                            cacheKey = "${fileId}_${activeVersionId.orEmpty()}"
                        )
                },
                streamFetcher = object : DownloadStreamFetcher {
                    override suspend fun downloadToFile(
                        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                        destinationFile: File,
                        onContentLengthKnown: suspend (Long) -> Unit,
                        onProgress: suspend (Long, Long?) -> Unit
                    ): DownloadStreamResult {
                        destinationFile.writeBytes(payload)
                        return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
                    }
                },
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = testDispatchers
            )
            val scheduler = InMemoryDownloadTaskScheduler(backgroundScope, engine)
            val repo = OfflineDownloadsRepositoryImpl(
                authRepository = FakeAuthRepository(studentSession()),
                downloadsDataSource = dataSource,
                storageManager = storageManager,
                taskScheduler = scheduler,
                dispatcherProvider = testDispatchers
            )

            repo.startDownload(sampleFile())
            // Suspend deterministically AFTER commitVerifiedTempFile placed document.pdf on disk
            // and BEFORE saveCompletedRecord writes the DataStore metadata record.
            updateEntered.await()
            val committedFile = storageManager.resolveCompletedFile("file-1", "v1")
            assertTrue(committedFile.exists())

            // Trigger cancel concurrently mid-commit while DataStore updateData is still paused inside NonCancellable.
            repo.cancelDownload("file-1", "v1")

            // Let DataStore persistence finish and flush all observers.
            allowUpdateFinish.complete(Unit)
            advanceUntilIdle()

            // Must preserve completed file, never emit CANCELLED, and expose COMPLETED only with valid file + record.
            assertTrue(committedFile.exists())
            assertTrue(committedFile.readBytes().contentEquals(payload))
            val verified = repo.getVerifiedLocalVersion("file-1", "v1")
            assertNotNull(verified)
            assertEquals(committedFile.absolutePath, verified?.localFilePath)

            val observed = repo.observeFileOfflineState("file-1", "v1").first()
            assertEquals(DownloadStatus.COMPLETED, observed.activeVersionDownload?.status)
            assertTrue(observed.hasActiveVersionLocalCopy)
        }

    @Test
    fun `cancel before commit aborts transfer removes temp file and never exposes COMPLETED without valid record`() =
        runTest {
            val streamReady = CompletableDeferred<Unit>()
            val holdStream = CompletableDeferred<Unit>()
            val harness = createHarness(
                scope = this,
                streamFetcher = object : DownloadStreamFetcher {
                    override suspend fun downloadToFile(
                        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                        destinationFile: File,
                        onContentLengthKnown: suspend (Long) -> Unit,
                        onProgress: suspend (Long, Long?) -> Unit
                    ): DownloadStreamResult {
                        destinationFile.writeBytes("%PDF-1.4\nbefore-commit-partial".toByteArray())
                        streamReady.complete(Unit)
                        holdStream.await()
                        return DownloadStreamResult(28L, 28L, null)
                    }
                }
            )

            harness.repository.startDownload(sampleFile())
            streamReady.await()

            harness.repository.cancelDownload("file-1", "v1")
            advanceUntilIdle()

            assertNull(harness.repository.getVerifiedLocalVersion("file-1", "v1"))
            assertFalse(harness.storageManager.resolveCompletedFile("file-1", "v1").exists())
            assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            val observed = harness.repository.observeFileOfflineState("file-1", "v1").first()
            assertEquals(DownloadStatus.CANCELLED, observed.activeVersionDownload?.status)
            assertFalse(observed.hasActiveVersionLocalCopy)
        }

    @Test
    fun `concurrent startDownload calls for same version deduplicate to a single enqueue`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val payload = "%PDF-1.4\nconcurrent-dedup".toByteArray()
            val fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    gate.await()
                    destinationFile.writeBytes(payload)
                    return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
                }
            }

            val harness = createHarness(scope = this, streamFetcher = fetcher)
            val file = sampleFile()

            val firstCall = async { harness.repository.startDownload(file) }
            val secondCall = async { harness.repository.startDownload(file) }
            val r1 = firstCall.await()
            val r2 = secondCall.await()

            assertTrue(r1 is AppResult.Success)
            assertTrue(r2 is AppResult.Success)
            assertEquals(1, harness.scheduler.enqueueCalls)

            gate.complete(Unit)
            advanceUntilIdle()
            assertNotNull(harness.repository.getVerifiedLocalVersion(file.id, file.activeVersionId))
        }

    @Test
    fun `corrupted or deleted local file after saving record invalidates completion and allows redownload`() =
        runTest {
            val validPayload = "%PDF-1.4\nhealthy-pdf-payload".toByteArray()
            val fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    destinationFile.writeBytes(validPayload)
                    return DownloadStreamResult(validPayload.size.toLong(), validPayload.size.toLong(), null)
                }
            }

            val harness = createHarness(scope = this, streamFetcher = fetcher)
            val file = sampleFile()

            harness.repository.startDownload(file)
            advanceUntilIdle()
            val saved = harness.repository.getVerifiedLocalVersion(file.id, file.activeVersionId)
            assertNotNull(saved)

            // Case A: File content gets corrupted on disk after saving DataStore record
            val localFile = File(saved!!.localFilePath)
            localFile.writeText("NOT_A_PDF")
            assertNull(harness.repository.getVerifiedLocalVersion(file.id, file.activeVersionId))
            val corruptedState = harness.repository.observeFileOfflineState(file.id, file.activeVersionId).first()
            assertFalse(corruptedState.hasActiveVersionLocalCopy)
            assertTrue(corruptedState.savedVersions.isEmpty())

            // Re-download repairs the corrupted file
            harness.repository.startDownload(file)
            advanceUntilIdle()
            assertNotNull(harness.repository.getVerifiedLocalVersion(file.id, file.activeVersionId))

            // Case B: File is deleted externally after saving record
            assertTrue(localFile.delete())
            assertNull(harness.repository.getVerifiedLocalVersion(file.id, file.activeVersionId))
            val deletedState = harness.repository.observeFileOfflineState(file.id, file.activeVersionId).first()
            assertFalse(deletedState.hasActiveVersionLocalCopy)
        }

    @Test
    fun `orphan cleanup removes dead process part and commit temp files while preserving active and completed files`() =
        runTest {
            val activeStarted = CompletableDeferred<Unit>()
            val releaseActive = CompletableDeferred<Unit>()
            val v1Payload = "%PDF-1.4\ncompleted-v1".toByteArray()
            val v2Payload = "%PDF-1.4\nactive-v2".toByteArray()

            val fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    return if (source.url.contains("v1")) {
                        destinationFile.writeBytes(v1Payload)
                        DownloadStreamResult(v1Payload.size.toLong(), v1Payload.size.toLong(), null)
                    } else {
                        destinationFile.writeBytes("%PDF-active-in-progress".toByteArray())
                        activeStarted.complete(Unit)
                        releaseActive.await()
                        destinationFile.writeBytes(v2Payload)
                        DownloadStreamResult(v2Payload.size.toLong(), v2Payload.size.toLong(), null)
                    }
                }
            }

            val harness = createHarness(scope = this, streamFetcher = fetcher)

            // 1) Complete v1 first
            harness.repository.startDownload(sampleFile(versionId = "v1", versionNumber = 1, versionLabel = "v1"))
            advanceUntilIdle()
            val completedV1 = harness.repository.getVerifiedLocalVersion("file-1", "v1")
            assertNotNull(completedV1)
            val completedV1File = File(completedV1!!.localFilePath)

            // 2) Simulate leftovers from an earlier killed process (.part + .commit_*.tmp)
            val tmpDir = File(harness.storageRoot, LocalPdfStorageManager.TEMP_DIR_NAME).apply { mkdirs() }
            val orphanedPart = File(tmpDir, "deadbeef_dead01_abc123.part").apply {
                writeText("%PDF-orphan-from-process-kill")
            }
            val orphanedCommitTmp = File(completedV1File.parentFile, ".commit_999999.tmp").apply {
                writeText("%PDF-orphan-commit")
            }

            // 3) Start an active v2 download so an active .part file is registered in-flight
            harness.repository.startDownload(sampleFile(versionId = "v2", versionNumber = 2, versionLabel = "v2"))
            activeStarted.await()

            val tempFilesBeforeCleanup = harness.storageManager.listAllTempFiles()
            assertEquals(2, tempFilesBeforeCleanup.size)
            val activeTempFile = tempFilesBeforeCleanup.first { it.absolutePath != orphanedPart.absolutePath }

            // 4) Run orphan cleanup mid-download
            harness.repository.cleanupOrphanedTempFiles()

            // Orphans deleted, active .part preserved, completed document.pdf preserved
            assertFalse(orphanedPart.exists())
            assertFalse(orphanedCommitTmp.exists())
            assertTrue(activeTempFile.exists())
            assertTrue(completedV1File.exists())

            // 5) Finish active v2 download cleanly
            releaseActive.complete(Unit)
            advanceUntilIdle()
            assertNotNull(harness.repository.getVerifiedLocalVersion("file-1", "v2"))
            assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
        }

    @Test
    fun `slow stalled http download cancellation closes socket releases resources and deletes part file`() =
        runBlocking {
            val firstChunkWritten = CountDownLatch(1)
            val allowServerSecondWrite = CountDownLatch(1)
            val serverFinished = CountDownLatch(1)
            val serverSawClosedSocket = AtomicBoolean(false)

            val totalPlannedBytes = 256L * 1024L
            val initialChunk = ("%PDF-1.4\n" + "A".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val secondChunk = ByteArray(64 * 1024) { 'B'.code.toByte() }

            val serverExecutor = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = serverExecutor
                createContext("/stalled.pdf") { exchange ->
                    exchange.responseHeaders.add("Content-Type", "application/pdf")
                    exchange.sendResponseHeaders(200, totalPlannedBytes)
                    try {
                        exchange.responseBody.use { body ->
                            body.write(initialChunk)
                            body.flush()
                            firstChunkWritten.countDown()

                            // Hold response mid-stream until the client cancels its blocked read().
                            allowServerSecondWrite.await(5, TimeUnit.SECONDS)
                            repeat(4) {
                                body.write(secondChunk)
                                body.flush()
                            }
                        }
                    } catch (_: IOException) {
                        serverSawClosedSocket.set(true)
                    } finally {
                        serverFinished.countDown()
                    }
                }
            }
            server.start()

            val storageRoot = tempFolder.newFolder("stalled_http_storage")
            val prefsFile = File(tempFolder.newFolder(), "stalled_http.preferences_pb")
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ioDataStore = PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { prefsFile }
            )
            val dataSource = OfflineDownloadsDataSource(ioDataStore)
            val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
            val realIoDispatchers = object : DispatcherProvider {
                override val main: CoroutineDispatcher = Dispatchers.IO
                override val io: CoroutineDispatcher = Dispatchers.IO
                override val default: CoroutineDispatcher = Dispatchers.Default
            }
            val port = server.address.port
            val engine = DownloadExecutionEngine(
                sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                        PdfSourceDescriptor.AuthorizedRemoteUrl(
                            url = "http://127.0.0.1:$port/stalled.pdf",
                            cacheKey = "${fileId}_${activeVersionId.orEmpty()}"
                        )
                },
                streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true),
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = realIoDispatchers
            )

            try {
                val clientReceivedChunk = CompletableDeferred<Unit>()
                val downloadJob = launch(Dispatchers.IO) {
                    engine.executeDownload(
                        request = OfflineDownloadRequest(
                            fileId = "file-slow",
                            versionId = "v1",
                            versionNumber = 1,
                            versionLabel = "v1",
                            fileTitle = "ملف بطيء"
                        ),
                        onProgress = { bytesDownloaded, _, _ ->
                            if (bytesDownloaded >= 32L * 1024L) {
                                clientReceivedChunk.complete(Unit)
                            }
                        }
                    )
                }

                // Wait until server sent the first chunk AND client actually wrote >= 32KB to `.part`
                // and is blocked inside `input.read(buffer)` waiting for the rest of the body.
                assertTrue(firstChunkWritten.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { clientReceivedChunk.await() }
                assertTrue(storageManager.listAllTempFiles().isNotEmpty())

                // Cancel while blocked in HTTP read; job must unblock & exit far below 30s READ_TIMEOUT_MS.
                downloadJob.cancel()
                withTimeout(5_000L) { downloadJob.join() }

                // Let server attempt writing again; closed socket must fail on server side and `.part` must be gone.
                allowServerSecondWrite.countDown()
                assertTrue(serverFinished.await(5, TimeUnit.SECONDS))
                assertTrue(serverSawClosedSocket.get())
                assertTrue(storageManager.listAllTempFiles().isEmpty())
                assertFalse(storageManager.resolveCompletedFile("file-slow", "v1").exists())
                assertNull(dataSource.getRecord("file-slow", "v1"))
            } finally {
                allowServerSecondWrite.countDown()
                dataStoreScope.cancel()
                server.stop(0)
                serverExecutor.shutdownNow()
            }
        }

    @Test
    fun `archiving and restoring study file in catalog preserves existing local downloaded copy`() =
        runTest {
            val pdfBytes = "%PDF-1.4\n%archived-retention-test\n".toByteArray() + ByteArray(2048) { 0x41 }
            val harness = createHarness(
                scope = this,
                streamFetcher = DownloadStreamFetcher { _, listener ->
                    listener.onBytesRead(pdfBytes.size.toLong(), pdfBytes.size.toLong())
                    DownloadStreamResult(
                        inputStream = pdfBytes.inputStream(),
                        expectedContentLength = pdfBytes.size.toLong()
                    )
                }
            )
            val studyFile = StudyFileMetadata(
                id = "physics-ch1",
                sectionId = "sec-1",
                title = "الفصل الأول",
                order = 1,
                isPublished = true,
                archiveStatus = ArchiveStatus.UNARCHIVED,
                activeVersionId = "v1",
                activeVersionNumber = 1,
                activeVersionLabel = "1.0"
            )
            val startResult = harness.repository.startDownload(studyFile)
            assertTrue(startResult is AppResult.Success)
            advanceUntilIdle()

            // Verify student downloaded v1 locally before admin archives the parent file.
            val savedBeforeArchive = harness.repository.getVerifiedLocalVersion("physics-ch1", "v1")
            assertNotNull(savedBeforeArchive)

            val adminCatalog = FakeAdminCatalogRepository().apply {
                studyFiles.add(studyFile)
                versions.add(
                    StudyFileVersionMetadata(
                        id = "v1",
                        fileId = "physics-ch1",
                        versionNumber = 1,
                        versionLabel = "1.0",
                        r2ObjectKey = "study-files/physics-ch1/versions/v1/document.pdf",
                        fileSizeBytes = pdfBytes.size.toLong(),
                        checksumSha256 = "e".repeat(64),
                        isPublished = true
                    )
                )
            }

            // Admin archives the published file -> atomically sets isPublished = false, then restores it.
            assertTrue(adminCatalog.setStudyFileArchived("physics-ch1", isArchived = true) is AppResult.Success)
            assertTrue(adminCatalog.studyFiles.single().isArchived)
            assertFalse(adminCatalog.studyFiles.single().isPublished)
            assertTrue(adminCatalog.setStudyFileArchived("physics-ch1", isArchived = false) is AppResult.Success)
            assertFalse(adminCatalog.studyFiles.single().isPublished)

            // Student's local downloaded version must remain untouched and readable offline.
            val savedAfterArchive = harness.repository.getVerifiedLocalVersion("physics-ch1", "v1")
            assertNotNull(savedAfterArchive)
            assertEquals("v1", savedAfterArchive?.versionId)
            assertEquals(1, harness.repository.getSavedVersionsForFile("physics-ch1").size)
            assertTrue(File(savedAfterArchive!!.localFilePath).exists())
        }
}
