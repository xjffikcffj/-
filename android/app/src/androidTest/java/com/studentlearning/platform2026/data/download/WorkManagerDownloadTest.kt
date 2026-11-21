package com.studentlearning.platform2026.data.download

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DefaultDispatcherProvider
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_BYTES_DOWNLOADED
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_FAILURE_REASON
import com.studentlearning.platform2026.data.download.WorkManagerDownloadScheduler.Companion.KEY_PROGRESS_PERCENT
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.data.repository.OfflineDownloadsRepositoryImpl
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Instrumentation tests verifying [WorkManagerDownloadScheduler] and [PdfDownloadWorker] against
 * Android's [WorkManagerTestInitHelper] and [TestListenableWorkerBuilder].
 *
 * Note: While this test suite verifies WorkManager enqueue/cancel/replace semantics, unique-work
 * persistence across recreated repository instances, and worker auth boundaries using real Android
 * WorkManager APIs, it runs inside an instrumentation harness and does not replace verification of
 * actual OS process death or device reboot on a physical/emulator device.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerDownloadTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var testScope: CoroutineScope

    private class LocalFakeAuthRepository(
        initialSession: AdminSession
    ) : AuthRepository {
        private val state = MutableStateFlow(initialSession)
        override val sessionFlow: StateFlow<AdminSession> = state.asStateFlow()

        fun setSession(session: AdminSession) {
            state.value = session
        }

        override suspend fun refreshAdminSession(forceTokenRefresh: Boolean): AppResult<AdminSession> =
            AppResult.Success(state.value)

        override suspend fun signInWithEmailAndPassword(
            email: String,
            password: String
        ): AppResult<AdminSession> = AppResult.Success(state.value)

        override suspend fun createStudentAccount(
            email: String,
            password: String,
            confirmPassword: String
        ): AppResult<AdminSession> = AppResult.Success(state.value)

        override suspend fun sendPasswordResetEmail(email: String): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun signOut(): AppResult<Unit> {
            state.value = AdminSession()
            return AppResult.Success(Unit)
        }

        override suspend fun requireAuthenticatedSession(): AdminSession {
            val current = state.value
            if (!current.isSignedIn || current.uid.isNullOrBlank()) {
                throw AdminException(AdminFailureReason.UNAUTHENTICATED)
            }
            return current
        }

        override suspend fun requireAuthorizedAdminSession(): AdminSession {
            val current = requireAuthenticatedSession()
            if (!current.role.canAccessAdminDashboard) {
                throw AdminException(AdminFailureReason.INSUFFICIENT_ROLE)
            }
            return current
        }
    }

    private fun studentSession() = AdminSession(
        isSignedIn = true,
        uid = "student-wm-1",
        email = "student@example.com",
        role = AdminRole.NONE,
        hasLoadedTokenClaims = true,
        areClaimsConfirmedCurrent = true
    )

    private fun sampleMetadata(versionId: String = "v1") = StudyFileMetadata(
        id = "wm-file-1",
        sectionId = "sec-1",
        title = "ملف اختبار WorkManager",
        description = "وصف",
        order = 1,
        isPublished = true,
        archiveStatus = ArchiveStatus.UNARCHIVED,
        activeVersionId = versionId,
        activeVersionNumber = 1,
        activeVersionLabel = versionId
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.filesDir, "wm_download_test_${UUID.randomUUID()}").apply { mkdirs() }
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        runCatching { testDir.deleteRecursively() }
    }

    private fun createEngine(
        authRepo: LocalFakeAuthRepository,
        fetcher: DownloadStreamFetcher,
        storageRoot: File = File(testDir, "storage"),
        prefsFile: File = File(testDir, "offline.preferences_pb")
    ): Triple<OfflineDownloadsDataSource, LocalPdfStorageManager, DownloadExecutionEngine> {
        val dataSource = OfflineDownloadsDataSource(
            PreferenceDataStoreFactory.create(scope = testScope, produceFile = { prefsFile })
        )
        val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
        val engine = DownloadExecutionEngine(
            sourceDataSource = object : PdfFileSourceDataSource {
                override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                    PdfSourceDescriptor.AuthorizedRemoteUrl(
                        url = "https://example.invalid/$fileId/${activeVersionId.orEmpty()}.pdf",
                        cacheKey = "${fileId}_${activeVersionId.orEmpty()}"
                    )
            },
            streamFetcher = fetcher,
            storageManager = storageManager,
            downloadsDataSource = dataSource,
            dispatcherProvider = DefaultDispatcherProvider()
        )
        return Triple(dataSource, storageManager, engine)
    }

    @Test
    fun scheduler_enqueueDeduplicateCancelAndRestart_withWorkManagerTestInitHelper() = runBlocking {
        val payload = "%PDF-1.4\nworkmanager-scheduler-ok".toByteArray()
        val authRepo = LocalFakeAuthRepository(studentSession())
        var gateAttempts = 0
        val secondAttemptCanFinish = CompletableDeferred<Unit>()
        val (dataSource, storageManager, engine) = createEngine(
            authRepo = authRepo,
            fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    gateAttempts++
                    if (gateAttempts == 1) {
                        // Hold first attempt if emulator network constraints are already satisfied
                        CompletableDeferred<Unit>().await()
                    }
                    secondAttemptCanFinish.await()
                    destinationFile.writeBytes(payload)
                    return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
                }
            }
        )

        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return if (workerClassName == PdfDownloadWorker::class.java.name) {
                    PdfDownloadWorker(appContext, workerParameters, authRepo, engine)
                } else {
                    null
                }
            }
        }

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(workerFactory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!

        val scheduler = WorkManagerDownloadScheduler(context)
        val request = OfflineDownloadRequest(
            fileId = "wm-file-1",
            versionId = "v1",
            versionNumber = 1,
            versionLabel = "v1",
            fileTitle = "ملف اختبار"
        )

        // 1) Enqueue first work request -> in progress (QUEUED or DOWNLOADING depending on network)
        val firstEnqueued = scheduler.enqueue(request, replaceExisting = false)
        assertTrue(firstEnqueued.status.isInProgress)
        assertNotNull(firstEnqueued.taskId)

        // 2) Duplicate enqueue with replaceExisting=false preserves the same taskId (KEEP)
        val duplicate = scheduler.enqueue(request, replaceExisting = false)
        assertEquals(firstEnqueued.taskId, duplicate.taskId)

        // 3) Cancel waits for WorkManager cancellation
        scheduler.cancel(request.fileId, request.versionId)
        val afterCancel = scheduler.getTaskState(request.fileId, request.versionId)
        assertEquals(DownloadStatus.CANCELLED, afterCancel?.status)

        // 4) Immediate restart with replaceExisting=true receives a new taskId and completes
        val restarted = scheduler.enqueue(request, replaceExisting = true)
        assertNotNull(restarted.taskId)
        assertNotEquals(firstEnqueued.taskId, restarted.taskId)
        assertTrue(restarted.status.isInProgress)

        runCatching { testDriver.setAllConstraintsMet(UUID.fromString(restarted.taskId!!)) }
        secondAttemptCanFinish.complete(Unit)

        val completedTask = withTimeout(10_000L) {
            scheduler.observeTasks().first { tasks ->
                tasks[request.fileId to request.versionId]?.status == DownloadStatus.COMPLETED
            }[request.fileId to request.versionId]
        }
        assertEquals(DownloadStatus.COMPLETED, completedTask?.status)
        val saved = dataSource.getRecord(request.fileId, request.versionId)
        assertNotNull(saved)
        assertTrue(storageManager.isStoredFileValid(saved!!))
    }

    @Test
    fun repository_reattachedAfterSimulatedRestart_restoresWorkManagerStateAndCompletes() = runBlocking {
        val payload = "%PDF-1.4\nsimulated-restart-restore".toByteArray()
        val authRepo = LocalFakeAuthRepository(studentSession())
        val allowFinish = CompletableDeferred<Unit>()
        val (dataSource, storageManager, engine) = createEngine(
            authRepo = authRepo,
            fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    allowFinish.await()
                    destinationFile.writeBytes(payload)
                    return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
                }
            }
        )

        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = if (workerClassName == PdfDownloadWorker::class.java.name) {
                PdfDownloadWorker(appContext, workerParameters, authRepo, engine)
            } else {
                null
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(workerFactory)
                .build()
        )
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val dispatchers = DefaultDispatcherProvider()

        val initialRepo = OfflineDownloadsRepositoryImpl(
            authRepository = authRepo,
            downloadsDataSource = dataSource,
            storageManager = storageManager,
            taskScheduler = WorkManagerDownloadScheduler(context),
            dispatcherProvider = dispatchers
        )

        val startResult = initialRepo.startDownload(sampleMetadata("v1"))
        assertTrue(startResult is AppResult.Success)
        val taskId = (startResult as AppResult.Success).data.taskId!!

        // Simulate recreating Repository & Scheduler (e.g., new session/process reconnecting to WorkManager DB)
        val restoredRepo = OfflineDownloadsRepositoryImpl(
            authRepository = authRepo,
            downloadsDataSource = dataSource,
            storageManager = storageManager,
            taskScheduler = WorkManagerDownloadScheduler(context),
            dispatcherProvider = dispatchers
        )
        val restoredInProgress = withTimeout(5_000L) {
            restoredRepo.observeFileOfflineState("wm-file-1", "v1").first {
                it.activeVersionDownload?.status?.isInProgress == true
            }
        }
        assertTrue(restoredInProgress.activeVersionDownload?.status?.isInProgress == true)

        runCatching { testDriver.setAllConstraintsMet(UUID.fromString(taskId)) }
        allowFinish.complete(Unit)
        val restoredCompleted = withTimeout(10_000L) {
            restoredRepo.observeFileOfflineState("wm-file-1", "v1").first {
                it.activeVersionDownload?.status == DownloadStatus.COMPLETED && it.hasActiveVersionLocalCopy
            }
        }
        assertEquals(DownloadStatus.COMPLETED, restoredCompleted.activeVersionDownload?.status)
    }

    @Test
    fun worker_signedInStudent_succeedsAndWritesVerifiedPdf() = runBlocking {
        val payload = "%PDF-1.4\nworker-direct-success".toByteArray()
        val authRepo = LocalFakeAuthRepository(studentSession())
        val (dataSource, storageManager, engine) = createEngine(
            authRepo = authRepo,
            fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult {
                    onContentLengthKnown(payload.size.toLong())
                    destinationFile.writeBytes(payload)
                    onProgress(payload.size.toLong(), payload.size.toLong())
                    return DownloadStreamResult(payload.size.toLong(), payload.size.toLong(), null)
                }
            }
        )

        val worker = TestListenableWorkerBuilder.from(context, PdfDownloadWorker::class.java)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = PdfDownloadWorker(appContext, workerParameters, authRepo, engine)
            })
            .setInputData(
                WorkManagerDownloadScheduler.buildInputData(
                    OfflineDownloadRequest(
                        fileId = "worker-file",
                        versionId = "v1",
                        versionNumber = 1,
                        versionLabel = "v1",
                        fileTitle = "ملف العامل"
                    )
                )
            )
            .build() as PdfDownloadWorker

        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(payload.size.toLong(), output.getLong(KEY_BYTES_DOWNLOADED, -1L))
        assertEquals(100, output.getInt(KEY_PROGRESS_PERCENT, -1))
        val saved = dataSource.getRecord("worker-file", "v1")
        assertNotNull(saved)
        assertTrue(storageManager.isStoredFileValid(saved!!))
    }

    @Test
    fun worker_blocksGuestAndSignOutBeforeExecution() = runBlocking {
        val authRepo = LocalFakeAuthRepository(AdminSession()) // Guest
        val (dataSource, storageManager, engine) = createEngine(
            authRepo = authRepo,
            fetcher = object : DownloadStreamFetcher {
                override suspend fun downloadToFile(
                    source: PdfSourceDescriptor.AuthorizedRemoteUrl,
                    destinationFile: File,
                    onContentLengthKnown: suspend (Long) -> Unit,
                    onProgress: suspend (Long, Long?) -> Unit
                ): DownloadStreamResult = error("Fetcher must never run for guest")
            }
        )

        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = PdfDownloadWorker(appContext, workerParameters, authRepo, engine)
        }
        val inputData = WorkManagerDownloadScheduler.buildInputData(
            OfflineDownloadRequest(fileId = "guest-file", versionId = "v1", fileTitle = "زائر")
        )

        val guestWorker = TestListenableWorkerBuilder.from(context, PdfDownloadWorker::class.java)
            .setWorkerFactory(factory)
            .setInputData(inputData)
            .build() as PdfDownloadWorker

        val guestResult = guestWorker.doWork()
        assertTrue(guestResult is ListenableWorker.Result.Failure)
        assertEquals(
            DownloadFailureReason.UNAUTHENTICATED_GUEST.name,
            (guestResult as ListenableWorker.Result.Failure).outputData.getString(KEY_FAILURE_REASON)
        )

        // Student schedules, then signs out BEFORE worker starts
        authRepo.setSession(studentSession())
        val preSignOutWorker = TestListenableWorkerBuilder.from(context, PdfDownloadWorker::class.java)
            .setWorkerFactory(factory)
            .setInputData(inputData)
            .build() as PdfDownloadWorker
        authRepo.signOut()

        val afterSignOutResult = preSignOutWorker.doWork()
        assertTrue(afterSignOutResult is ListenableWorker.Result.Failure)
        assertEquals(
            DownloadFailureReason.UNAUTHENTICATED_GUEST.name,
            (afterSignOutResult as ListenableWorker.Result.Failure).outputData.getString(KEY_FAILURE_REASON)
        )
        assertNull(dataSource.getRecord("guest-file", "v1"))
        assertTrue(storageManager.listAllTempFiles().isEmpty())
    }

    @Test
    fun worker_signOutMidDownload_abortsAndCleansPartWhilePreservingCompletedV1() = runBlocking {
        val v1Payload = "%PDF-1.4\nexisting-v1".toByteArray()
        val authRepo = LocalFakeAuthRepository(studentSession())
        val v2Started = CompletableDeferred<Unit>()
        val holdV2 = CompletableDeferred<Unit>()

        val (dataSource, storageManager, engine) = createEngine(
            authRepo = authRepo,
            fetcher = object : DownloadStreamFetcher {
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
                        destinationFile.writeBytes("%PDF-mid-stream".toByteArray())
                        v2Started.complete(Unit)
                        holdV2.await()
                        DownloadStreamResult(15L, 100L, null)
                    }
                }
            }
        )

        // Complete v1 while signed in
        engine.executeDownload(
            OfflineDownloadRequest(fileId = "signout-file", versionId = "v1", fileTitle = "ملف")
        )
        val completedV1 = dataSource.getRecord("signout-file", "v1")
        assertNotNull(completedV1)
        assertTrue(File(completedV1!!.localFilePath).exists())

        val v2Worker = TestListenableWorkerBuilder.from(context, PdfDownloadWorker::class.java)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = PdfDownloadWorker(appContext, workerParameters, authRepo, engine)
            })
            .setInputData(
                WorkManagerDownloadScheduler.buildInputData(
                    OfflineDownloadRequest(fileId = "signout-file", versionId = "v2", fileTitle = "ملف")
                )
            )
            .build() as PdfDownloadWorker

        val signOutJob = launch {
            v2Started.await()
            delay(50)
            authRepo.signOut()
        }

        val v2Result = v2Worker.doWork()
        signOutJob.join()

        assertTrue(v2Result is ListenableWorker.Result.Failure)
        assertEquals(
            DownloadFailureReason.UNAUTHENTICATED_GUEST.name,
            (v2Result as ListenableWorker.Result.Failure).outputData.getString(KEY_FAILURE_REASON)
        )
        assertTrue(storageManager.listAllTempFiles().isEmpty())
        assertNull(dataSource.getRecord("signout-file", "v2"))
        // Existing completed v1 on disk & metadata remains completely intact
        assertTrue(File(completedV1.localFilePath).exists())
        assertNotNull(dataSource.getRecord("signout-file", "v1"))
    }
}
