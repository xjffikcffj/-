package com.studentlearning.platform2026.data.remote.backend

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.download.DownloadExecutionEngine
import com.studentlearning.platform2026.data.download.DownloadTaskScheduler
import com.studentlearning.platform2026.data.download.HttpDownloadStreamFetcher
import com.studentlearning.platform2026.data.download.LocalPdfStorageManager
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.pdf.BrokeredPdfFileSourceDataSource
import com.studentlearning.platform2026.data.pdf.BrokeredPdfVersionStorageDataSource
import com.studentlearning.platform2026.data.pdf.HttpPdfContentFetcher
import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.data.repository.AdminCatalogRepositoryImpl
import com.studentlearning.platform2026.data.repository.OfflineDownloadsRepositoryImpl
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.LoopbackHttpExchange
import com.studentlearning.platform2026.util.LoopbackHttpServer
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class BackendHttpIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    private lateinit var server: LoopbackHttpServer
    private lateinit var baseUrl: String
    private var serverExecutor = Executors.newCachedThreadPool()

    private fun tokenProvider(token: () -> String?): AuthTokenProvider = object : AuthTokenProvider {
        override suspend fun getIdTokenOrNull(forceRefresh: Boolean): String? = token()
    }

    private val pdfPayload = ("%PDF-1.7\n%Stage8 integration payload\n" +
        "1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n").toByteArray(Charsets.UTF_8)
    private val pdfSha256 = sha256Hex(pdfPayload)

    @Volatile
    private var uploadedStagingBytes: ByteArray? = null

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var currentAttemptId: String? = null

    @Volatile
    private var versionVerificationStatus: String = "NONE"

    @Volatile
    private var versionPublished: Boolean = false

    @Volatile
    private var lastObservedDownloadAuth: String? = null

    @Volatile
    private var denyCatalogNextRequest: Boolean = false

    @Volatile
    private var serveCorruptedDownloadBytes: Boolean = false

    @Volatile
    private var scriptedAdminError: Pair<Int, String>? = null

    @Volatile
    private var scriptedR2PutError: Pair<Int, String>? = null

    @Volatile
    private var includeDraftV2InVersionsList: Boolean = false

    @Volatile
    private var lastPublishedVersionId: String? = null

    @Volatile
    private var includeNonUnarchivedPublicFiles: Boolean = false

    @Before
    fun setUpServer() {
        scriptedAdminError = null
        scriptedR2PutError = null
        includeDraftV2InVersionsList = false
        lastPublishedVersionId = null
        includeNonUnarchivedPublicFiles = false
        server = LoopbackHttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        val port = server.address.port
        baseUrl = "http://127.0.0.1:$port"

        server.createContext("/") { exchange ->
            handleRequest(exchange)
        }
        server.start()
    }

    @After
    fun tearDownServer() {
        server.stop(0)
        serverExecutor.shutdownNow()
    }

    @Test
    fun `catalog client reads live Arabic hierarchy and pins size and sha256 metadata over HTTP`() = runTest {
        val dataSource = BackendCatalogDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { "student-jwt" }
        )

        val branches = dataSource.fetchPublishedBranches()
        assertEquals(1, branches.size)
        assertEquals("scientific", branches.first().id)
        assertEquals("السادس العلمي", branches.first().name)

        val subjects = dataSource.fetchPublishedSubjects("scientific")
        assertEquals(listOf("physics"), subjects.map { it.id })

        val sections = dataSource.fetchPublishedSections("physics")
        assertEquals(listOf("sec-1"), sections.map { it.id })

        val files = dataSource.fetchPublishedStudyFiles("sec-1")
        assertEquals(1, files.size)
        val file = files.first()
        assertEquals("file-1", file.id)
        assertEquals(ArchiveStatus.UNARCHIVED, file.archiveStatus)
        assertEquals("v1", file.activeVersionId)
        assertEquals(1, file.activeVersionNumber)
        assertEquals(pdfPayload.size.toLong(), file.activeVersionFileSizeBytes)
        assertEquals(pdfSha256, file.activeVersionChecksumSha256)

        // Items missing archiveStatus or with ARCHIVED / UNSPECIFIED must be filtered fail-closed.
        includeNonUnarchivedPublicFiles = true
        val filteredFiles = dataSource.fetchPublishedStudyFiles("sec-1")
        assertEquals(listOf("file-1"), filteredFiles.map { it.id })
        includeNonUnarchivedPublicFiles = false

        denyCatalogNextRequest = true
        try {
            dataSource.fetchPublishedBranches()
            fail("Expected PERMISSION_DENIED on HTTP 403")
        } catch (expected: CatalogException) {
            assertEquals(CatalogFailureReason.PERMISSION_DENIED, expected.reason)
        }
    }

    @Test
    fun `full admin version upload verify and publish pipeline runs over live HTTP`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "admin-uid",
                email = "admin@example.com",
                role = AdminRole.ADMIN,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true)
        val backendAdmin = BackendAdminVersionDataSource(
            httpClient = httpClient,
            authTokenProvider = tokenProvider { "admin-jwt" }
        )
        val hybridRemote = HybridAdminCatalogDataSource(
            firestoreDataSource = NoOpCatalogMetadataRemoteDataSource(),
            backendVersionDataSource = backendAdmin
        )
        val broker = BrokeredPdfVersionStorageDataSource(
            brokerEndpointBaseUrl = baseUrl,
            backendUploadClient = backendAdmin,
            allowInsecureHttpForTesting = true
        )
        assertEquals(PdfBrokerConfigurationStatus.CONFIGURED, broker.getBrokerStatus())

        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = hybridRemote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = broker
        )

        val created = repository.createStudyFileVersion(
            fileId = "file-1",
            versionNumber = 1,
            versionLabel = "1.0",
            versionNotes = "النسخة المعتمدة",
            publishAsActive = false,
            customVersionId = "v1"
        )
        assertTrue(created is AppResult.Success)
        assertFalse((created as AppResult.Success).data.hasUploadedPdfBinary)

        val uploadResult = repository.uploadAndVerifyStudyFileVersionPdf(
            fileId = "file-1",
            versionId = "v1",
            binarySource = PdfUploadBinarySource(
                sizeBytes = pdfPayload.size.toLong(),
                sha256Hex = pdfSha256,
                displayName = "physics-v1.pdf",
                openInputStream = { pdfPayload.inputStream() }
            )
        )
        assertTrue(uploadResult is AppResult.Success)
        val proof = (uploadResult as AppResult.Success).data
        assertEquals(pdfPayload.size.toLong(), proof.fileSizeBytes)
        assertEquals(pdfSha256, proof.checksumSha256)
        assertTrue(uploadedStagingBytes?.contentEquals(pdfPayload) == true)

        val fetchedVersions = repository.getStudyFileVersions("file-1")
        assertTrue(fetchedVersions is AppResult.Success)
        val verifiedVersion = (fetchedVersions as AppResult.Success).data.first()
        assertEquals("VERIFIED", verifiedVersion.binaryVerificationStatus)
        assertNull(verifiedVersion.r2ObjectKey)
        assertEquals(pdfSha256, verifiedVersion.checksumSha256)
        assertEquals("22222222-2222-4222-8222-222222222222", verifiedVersion.verifiedAttemptId)
        assertTrue(verifiedVersion.hasUploadedPdfBinary)

        val published = repository.publishStudyFileVersion("file-1", "v1")
        assertTrue(published is AppResult.Success)
        assertTrue(versionPublished)
        assertEquals("v1", lastPublishedVersionId)
    }

    @Test
    fun `F-AND-2 verified version DTO never synthesizes r2ObjectKey and fails closed if proof metadata is incomplete`() = runTest {
        versionVerificationStatus = "VERIFIED"
        currentSessionId = "11111111-1111-4111-8111-111111111111"
        currentAttemptId = "22222222-2222-4222-8222-222222222222"

        val backendAdmin = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { "admin-jwt" }
        )

        val versions = backendAdmin.fetchStudyFileVersions("file-1")
        assertEquals(1, versions.size)
        val version = versions.first()
        assertNull(version.r2ObjectKey)
        assertTrue(version.hasUploadedPdfBinary)
        assertEquals(pdfSha256, version.checksumSha256)
        assertEquals("22222222-2222-4222-8222-222222222222", version.verifiedAttemptId)

        val missingAttemptProof = version.copy(verifiedAttemptId = null)
        assertFalse(missingAttemptProof.hasUploadedPdfBinary)
    }

    @Test
    fun `F-AND-1 backend HTTP error codes map accurately to AdminValidationError and AdminFailureReason`() = runTest {
        val backendAdmin = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { "admin-jwt" }
        )

        val verifyCases = listOf(
            422 to "INVALID_PDF_HEADER" to AdminValidationError.VERSION_UPLOAD_INVALID_PDF,
            422 to "SIZE_MISMATCH" to AdminValidationError.VERSION_UPLOAD_SIZE_MISMATCH,
            422 to "SHA256_MISMATCH" to AdminValidationError.VERSION_UPLOAD_SHA256_MISMATCH,
            410 to "SESSION_EXPIRED" to AdminValidationError.VERSION_UPLOAD_SESSION_EXPIRED,
            412 to "ETAG_MISMATCH" to AdminValidationError.VERSION_UPLOAD_ETAG_MISMATCH,
            409 to "VERIFY_IN_PROGRESS" to AdminValidationError.VERSION_UPLOAD_SESSION_CONFLICT,
            409 to "SESSION_SUPERSEDED" to AdminValidationError.VERSION_UPLOAD_SESSION_CONFLICT,
            409 to "VERSION_ALREADY_PUBLISHED" to AdminValidationError.VERSION_ALREADY_PUBLISHED_IMMUTABLE,
            409 to "VERSION_ALREADY_VERIFIED" to AdminValidationError.VERSION_ALREADY_VERIFIED_IMMUTABLE,
            404 to "R2_OBJECT_MISSING" to AdminValidationError.VERSION_PDF_BINARY_MISSING,
            503 to "R2_NOT_CONFIGURED" to AdminValidationError.R2_STORAGE_NOT_CONFIGURED,
            400 to "VALIDATION_ERROR" to AdminValidationError.BACKEND_REQUEST_REJECTED
        )

        for ((statusAndCode, expectedValidation) in verifyCases) {
            val (status, code) = statusAndCode
            scriptedAdminError = status to code
            try {
                backendAdmin.verifyUploadedPdf("file-1", "v1", "sess-1")
                fail("Expected AdminException for $status $code")
            } catch (expected: AdminException) {
                assertEquals(
                    "Unexpected reason for $status $code",
                    AdminFailureReason.VALIDATION_ERROR,
                    expected.reason
                )
                assertEquals(
                    "Unexpected validationError for $status $code",
                    expectedValidation,
                    expected.validationError
                )
            }
        }

        scriptedAdminError = 409 to "SIZE_MISMATCH"
        try {
            backendAdmin.publishStudyFileVersion("file-1", "v1")
            fail("Expected AdminException for 409 SIZE_MISMATCH on publish")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.VERSION_PDF_BINARY_MISSING, expected.validationError)
        }

        scriptedAdminError = 502 to "COPY_FAILED"
        try {
            backendAdmin.verifyUploadedPdf("file-1", "v1", "sess-1")
            fail("Expected NETWORK_UNAVAILABLE for 502 COPY_FAILED")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.NETWORK_UNAVAILABLE, expected.reason)
        }
    }

    @Test
    fun `R2 presigned PUT XML errors map cleanly without Firestore confusion or leaking signed URLs`() = runTest {
        val backendAdmin = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { "admin-jwt" }
        )
        val signedSlot = com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot(
            fileId = "file-1",
            versionId = "v1",
            r2ObjectKey = "uploads-staging/file-1/v1/sess.pdf",
            signedUploadUrl = "$baseUrl/r2-staging/file-1/v1/sess.pdf?X-Amz-Signature=SECRET_SIG_TOKEN_123",
            uploadSessionId = "sess-xml-1",
            requiredHeaders = mapOf("Content-Type" to "application/pdf")
        )
        val binarySource = PdfUploadBinarySource(
            sizeBytes = pdfPayload.size.toLong(),
            sha256Hex = pdfSha256,
            displayName = "test.pdf",
            openInputStream = { pdfPayload.inputStream() }
        )

        // 1) R2 HTTP 403 XML with expired presigned URL -> VERSION_UPLOAD_SESSION_EXPIRED (never PERMISSION_DENIED)
        scriptedR2PutError = 403 to """<?xml version="1.0" encoding="UTF-8"?><Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>"""
        try {
            backendAdmin.uploadPdfToSlot(signedSlot, binarySource)
            fail("Expected VERSION_UPLOAD_SESSION_EXPIRED for expired R2 presigned PUT")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.VERSION_UPLOAD_SESSION_EXPIRED, expected.validationError)
            assertFalse(expected.message.orEmpty().contains("SECRET_SIG_TOKEN_123"))
            assertFalse(expected.message.orEmpty().contains("<Error>"))
        }

        // 2) R2 HTTP 403 XML with SignatureDoesNotMatch -> VERSION_UPLOAD_R2_REJECTED (never PERMISSION_DENIED)
        scriptedR2PutError = 403 to """<?xml version="1.0" encoding="UTF-8"?><Error><Code>SignatureDoesNotMatch</Code><Message>The request signature we calculated does not match SECRET_SIG_TOKEN_123</Message></Error>"""
        try {
            backendAdmin.uploadPdfToSlot(signedSlot, binarySource)
            fail("Expected VERSION_UPLOAD_R2_REJECTED for rejected R2 presigned PUT")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.VERSION_UPLOAD_R2_REJECTED, expected.validationError)
            assertFalse(expected.message.orEmpty().contains("SECRET_SIG_TOKEN_123"))
            assertFalse(expected.message.orEmpty().contains("<?xml"))
        }

        // 3) R2 HTTP 503 XML -> NETWORK_UNAVAILABLE
        scriptedR2PutError = 503 to """<Error><Code>SlowDown</Code><Message>Reduce rate</Message></Error>"""
        try {
            backendAdmin.uploadPdfToSlot(signedSlot, binarySource)
            fail("Expected NETWORK_UNAVAILABLE for R2 503 XML")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.NETWORK_UNAVAILABLE, expected.reason)
        }

        // 4) Backend JSON HTTP 403 PRIVILEGED_FIELD_FORBIDDEN remains distinct (PERMISSION_DENIED)
        scriptedAdminError = 403 to "PRIVILEGED_FIELD_FORBIDDEN"
        try {
            backendAdmin.publishStudyFileVersion("file-1", "v1")
            fail("Expected PERMISSION_DENIED for Backend 403 PRIVILEGED_FIELD_FORBIDDEN")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.PERMISSION_DENIED, expected.reason)
            assertNull(expected.validationError)
        }
    }

    @Test
    fun `R2 uploadPdfToSlot IOException sanitizes signed URL secrets and preserves CancellationException`() = runTest {
        val backendAdmin = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { "admin-jwt" }
        )
        val signedUrlWithToken = "https://example.invalid/upload?X-Amz-Signature=SECRET_SIG_TOKEN_123"
        val signedSlot = com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot(
            fileId = "file-1",
            versionId = "v1",
            r2ObjectKey = "uploads-staging/file-1/v1/sess.pdf",
            signedUploadUrl = signedUrlWithToken,
            uploadSessionId = "sess-io-1",
            requiredHeaders = mapOf("Content-Type" to "application/pdf")
        )

        // 1) IOException (including nested cause) carrying signed URL & signature must map to
        //    NETWORK_UNAVAILABLE without leaking secrets in message, any cause, or stackTraceToString.
        val ioFailingSource = PdfUploadBinarySource(
            sizeBytes = pdfPayload.size.toLong(),
            sha256Hex = pdfSha256,
            displayName = "test.pdf",
            openInputStream = {
                val rootCause = java.net.SocketTimeoutException("Timed out connecting to $signedUrlWithToken")
                throw IOException("Connection reset while uploading to $signedUrlWithToken", rootCause)
            }
        )
        try {
            backendAdmin.uploadPdfToSlot(signedSlot, ioFailingSource)
            fail("Expected AdminException(NETWORK_UNAVAILABLE) on IOException")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.NETWORK_UNAVAILABLE, expected.reason)
            assertNull(expected.validationError)
            assertFalse(expected.message.orEmpty().contains("SECRET_SIG_TOKEN_123"))
            assertFalse(expected.message.orEmpty().contains(signedUrlWithToken))
            assertFalse(expected.message.orEmpty().contains("X-Amz-Signature"))

            var currentCause: Throwable? = expected.cause
            while (currentCause != null) {
                val causeMsg = currentCause.message.orEmpty()
                assertFalse(causeMsg.contains("SECRET_SIG_TOKEN_123"))
                assertFalse(causeMsg.contains(signedUrlWithToken))
                assertFalse(causeMsg.contains("https://example.invalid/upload"))
                assertFalse(causeMsg.contains("X-Amz-Signature"))
                currentCause = currentCause.cause
            }

            val fullTrace = expected.stackTraceToString()
            assertFalse(fullTrace.contains("SECRET_SIG_TOKEN_123"))
            assertFalse(fullTrace.contains(signedUrlWithToken))
            assertFalse(fullTrace.contains("X-Amz-Signature"))
            assertTrue(expected.cause?.message.orEmpty().contains("IOException"))
        }

        // 2) CancellationException must propagate directly without being converted into NETWORK_UNAVAILABLE.
        val cancellingSource = PdfUploadBinarySource(
            sizeBytes = pdfPayload.size.toLong(),
            sha256Hex = pdfSha256,
            displayName = "test.pdf",
            openInputStream = {
                throw CancellationException("Upload cancelled for $signedUrlWithToken")
            }
        )
        try {
            backendAdmin.uploadPdfToSlot(signedSlot, cancellingSource)
            fail("Expected CancellationException to propagate untouched")
        } catch (cancelled: CancellationException) {
            assertTrue(cancelled.message.orEmpty().contains("Upload cancelled"))
        }
    }

    @Test
    fun `F-AND-3 setStudyFilePublished respects activeVersionId and never auto-publishes draft versions`() = runTest {
        versionVerificationStatus = "VERIFIED"
        currentSessionId = "11111111-1111-4111-8111-111111111111"
        currentAttemptId = "22222222-2222-4222-8222-222222222222"
        includeDraftV2InVersionsList = true
        lastPublishedVersionId = null

        var currentFileMeta = StudyFileMetadata(
            id = "file-1",
            sectionId = "sec-1",
            title = "ملزمة الفصل الأول",
            order = 1,
            isPublished = false,
            archiveStatus = ArchiveStatus.UNARCHIVED,
            activeVersionId = null,
            activeVersionNumber = null
        )
        var archiveMutationCalls = 0
        val firestoreFake = object : NoOpCatalogMetadataRemoteDataSource() {
            override suspend fun fetchStudyFileMetadata(fileId: String): StudyFileMetadata? =
                if (fileId == "file-1") currentFileMeta else null

            override suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean, actorUid: String) {
                archiveMutationCalls++
            }
        }
        val backendAdmin = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { "admin-jwt" }
        )
        val hybridRemote = HybridAdminCatalogDataSource(
            firestoreDataSource = firestoreFake,
            backendVersionDataSource = backendAdmin
        )

        // 1) No activeVersionId -> must refuse even though verified draft v2 exists.
        try {
            hybridRemote.setStudyFilePublished("file-1", isPublished = true, actorUid = "admin-uid")
            fail("Expected STUDY_FILE_NO_ACTIVE_VERSION when activeVersionId is null")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION, expected.validationError)
        }
        assertNull(lastPublishedVersionId)

        // 2) Active version is v1 while newer verified draft v2 exists -> must republish ONLY v1.
        currentFileMeta = currentFileMeta.copy(
            activeVersionId = "v1",
            activeVersionNumber = 1
        )
        hybridRemote.setStudyFilePublished("file-1", isPublished = true, actorUid = "admin-uid")
        assertEquals("v1", lastPublishedVersionId)

        // 3) Archived parent file -> must refuse.
        lastPublishedVersionId = null
        currentFileMeta = currentFileMeta.copy(archiveStatus = ArchiveStatus.ARCHIVED)
        try {
            hybridRemote.setStudyFilePublished("file-1", isPublished = true, actorUid = "admin-uid")
            fail("Expected FILE_ARCHIVED_CANNOT_PUBLISH_VERSION when file is archived")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION, expected.validationError)
        }
        assertNull(lastPublishedVersionId)

        // 4) Unspecified parent archive status -> must fail closed on both publish AND setStudyFileArchived.
        currentFileMeta = currentFileMeta.copy(archiveStatus = ArchiveStatus.UNSPECIFIED)
        try {
            hybridRemote.setStudyFilePublished("file-1", isPublished = true, actorUid = "admin-uid")
            fail("Expected ARCHIVE_STATE_UNSPECIFIED when file archive status is unspecified")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, expected.validationError)
        }
        try {
            hybridRemote.setStudyFileArchived("file-1", isArchived = true, actorUid = "admin-uid")
            fail("Expected ARCHIVE_STATE_UNSPECIFIED when archiving UNSPECIFIED file via Hybrid")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, expected.validationError)
        }
        assertEquals(0, archiveMutationCalls)
        assertNull(lastPublishedVersionId)
    }

    @Test
    fun `F-2FR BackendAdminVersionDataSource preserves tri-state archiveStatus and maps ARCHIVE_STATE_UNSPECIFIED`() = runTest {
        val backendAdmin = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { "admin-jwt" }
        )

        val versions = backendAdmin.fetchStudyFileVersions("file-tristate")
        val byId = versions.associateBy { it.id }

        assertEquals(ArchiveStatus.UNARCHIVED, byId.getValue("v_unarchived").archiveStatus)
        assertTrue(byId.getValue("v_unarchived").isExplicitlyUnarchived)

        assertEquals(ArchiveStatus.ARCHIVED, byId.getValue("v_archived").archiveStatus)
        assertTrue(byId.getValue("v_archived").isArchived)

        assertEquals(ArchiveStatus.UNSPECIFIED, byId.getValue("v_unspecified").archiveStatus)
        assertTrue(byId.getValue("v_unspecified").isArchiveStatusUnspecified)
        assertFalse(byId.getValue("v_unspecified").isExplicitlyUnarchived)

        // Missing, null, non-boolean, missing archiveStatus, or contradictory pairs MUST all yield UNSPECIFIED (F-2G-4).
        listOf(
            "v_invalid_archive_status",
            "v_missing_archive_null",
            "v_missing_archive_string",
            "v_missing_archive_false",
            "v_missing_archive_true",
            "v_contradict_unarchived_true",
            "v_contradict_archived_false",
            "v_unarchived_null_bool",
            "v_archived_null_bool",
            "v_unarchived_string_bool"
        ).forEach { id ->
            val v = byId.getValue(id)
            assertEquals("Expected UNSPECIFIED for $id", ArchiveStatus.UNSPECIFIED, v.archiveStatus)
            assertTrue("Expected isArchiveStatusUnspecified for $id", v.isArchiveStatusUnspecified)
            assertFalse("Expected !isExplicitlyUnarchived for $id", v.isExplicitlyUnarchived)
        }

        scriptedAdminError = 409 to "ARCHIVE_STATE_UNSPECIFIED"
        try {
            backendAdmin.publishStudyFileVersion("file-1", "v1")
            fail("Expected ARCHIVE_STATE_UNSPECIFIED on publish")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, expected.validationError)
        }

        scriptedAdminError = 409 to "ARCHIVE_STATE_UNSPECIFIED"
        try {
            backendAdmin.verifyUploadedPdf("file-1", "v1", "11111111-1111-4111-8111-111111111111")
            fail("Expected ARCHIVE_STATE_UNSPECIFIED on verify")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.VALIDATION_ERROR, expected.reason)
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, expected.validationError)
        }
    }

    @Test
    fun `stream allows guest while download enforces Bearer token and pins catalog sha256`() = runTest {
        var token: String? = null
        val sourceDataSource = BrokeredPdfFileSourceDataSource(
            brokerEndpointBaseUrl = baseUrl,
            authTokenProvider = tokenProvider { token },
            allowInsecureHttpForTesting = true
        )
        val catalogDataSource = BackendCatalogDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = baseUrl, allowInsecureHttpForTesting = true),
            authTokenProvider = tokenProvider { token }
        )

        // 1) Guest can stream without token.
        val streamDescriptor = sourceDataSource.resolvePdfSource("file-1", "v1") as PdfSourceDescriptor.AuthorizedRemoteUrl
        assertTrue(streamDescriptor.headers.isEmpty())
        val contentFetcher = HttpPdfContentFetcher(
            sessionCacheDir = tempFolder.newFolder("viewer_cache"),
            allowInsecureHttpForTesting = true
        )
        val prepared = contentFetcher.prepareReadableFile(streamDescriptor, "file-1")
        assertTrue(prepared.file.exists())
        assertTrue(prepared.file.readBytes().contentEquals(pdfPayload))

        // 2) Student downloads with Bearer token and pinned catalog size/checksum.
        val catalogFile = catalogDataSource.fetchPublishedStudyFiles("sec-1").first()
        token = "student-jwt"

        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempFolder.newFolder(), "downloads.preferences_pb") }
        )
        val downloadsDataSource = OfflineDownloadsDataSource(dataStore)
        val storageManager = LocalPdfStorageManager(
            storageRoot = tempFolder.newFolder("offline_files"),
            usableSpaceProvider = { 64L * 1024L * 1024L }
        )
        val engine = DownloadExecutionEngine(
            sourceDataSource = sourceDataSource,
            streamFetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = true),
            storageManager = storageManager,
            downloadsDataSource = downloadsDataSource,
            dispatcherProvider = testDispatchers
        )
        var capturedRequest: OfflineDownloadRequest? = null
        val capturingScheduler = object : DownloadTaskScheduler {
            private val tasksFlow = MutableStateFlow<Map<Pair<String, String>, DownloadTaskState>>(emptyMap())
            override fun observeTasks(): Flow<Map<Pair<String, String>, DownloadTaskState>> = tasksFlow
            override suspend fun getTaskState(fileId: String, versionId: String): DownloadTaskState? =
                tasksFlow.value[fileId to versionId]

            override suspend fun enqueue(
                request: OfflineDownloadRequest,
                replaceExisting: Boolean
            ): DownloadTaskState {
                capturedRequest = request
                val queued = DownloadTaskState(
                    fileId = request.fileId,
                    versionId = request.versionId,
                    status = DownloadStatus.QUEUED,
                    totalBytes = request.expectedFileSizeBytes
                )
                tasksFlow.update { it + ((request.fileId to request.versionId) to queued) }
                return queued
            }

            override suspend fun cancel(fileId: String, versionId: String) = Unit
        }
        val authRepo = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val repo = OfflineDownloadsRepositoryImpl(
            authRepository = authRepo,
            downloadsDataSource = downloadsDataSource,
            storageManager = storageManager,
            taskScheduler = capturingScheduler,
            dispatcherProvider = testDispatchers
        )

        val enqueued = repo.startDownload(catalogFile)
        assertTrue(enqueued is AppResult.Success)
        val request = capturedRequest
        assertNotNull(request)
        assertEquals(pdfPayload.size.toLong(), request!!.expectedFileSizeBytes)
        assertEquals(pdfSha256, request.expectedChecksumSha256)

        val localSaved = engine.executeDownload(request)
        assertEquals("Bearer student-jwt", lastObservedDownloadAuth)
        assertEquals(pdfSha256, localSaved.checksumSha256)
        assertTrue(File(localSaved.localFilePath).readBytes().contentEquals(pdfPayload))

        // 3) Tampered server payload mismatches pinned SHA-256 and is rejected atomically.
        serveCorruptedDownloadBytes = true
        val secondVersionRequest = request.copy(versionId = "v2")
        try {
            engine.executeDownload(secondVersionRequest)
            fail("Expected INTEGRITY_CHECK_FAILED on corrupted payload")
        } catch (expected: DownloadException) {
            assertEquals(DownloadFailureReason.INTEGRITY_CHECK_FAILED, expected.reason)
        }
    }

    private fun handleRequest(exchange: LoopbackHttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod.uppercase()
        val authHeader = exchange.requestHeaders.getFirst("Authorization")

        when {
            denyCatalogNextRequest && path.startsWith("/api/catalog/") -> {
                denyCatalogNextRequest = false
                sendJson(
                    exchange,
                    403,
                    """{"error":{"code":"PERMISSION_DENIED","message":"Forbidden"}}"""
                )
            }

            method == "GET" && path == "/api/catalog/branches" -> {
                sendJson(
                    exchange,
                    200,
                    """{"items":[{"id":"scientific","name":"السادس العلمي","order":1,"referencePath":"branches/scientific"}]}"""
                )
            }

            method == "GET" && path == "/api/catalog/branches/scientific/subjects" -> {
                sendJson(
                    exchange,
                    200,
                    """{"items":[{"id":"physics","branchId":"scientific","name":"الفيزياء","order":1,"branchReferencePath":"branches/scientific"}]}"""
                )
            }

            method == "GET" && path == "/api/catalog/subjects/physics/sections" -> {
                sendJson(
                    exchange,
                    200,
                    """{"items":[{"id":"sec-1","subjectId":"physics","name":"الفصل الأول","order":1,"subjectReferencePath":"subjects/physics"}]}"""
                )
            }

            method == "GET" && path == "/api/catalog/sections/sec-1/study-files" -> {
                val validItem = """{"id":"file-1","sectionId":"sec-1","title":"ملزمة الفصل الأول","description":"شرح","order":1,"archiveStatus":"UNARCHIVED","mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"activeVersionLabel":"1.0","activeVersionPublishedAtEpochMillis":1700000000000,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"}"""
                val extraItems = if (includeNonUnarchivedPublicFiles) {
                    """,{"id":"file-missing-status","sectionId":"sec-1","title":"بلا حالة","order":2,"mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"},{"id":"file-archived","sectionId":"sec-1","title":"مؤرشف","order":3,"archiveStatus":"ARCHIVED","mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"},{"id":"file-unspecified","sectionId":"sec-1","title":"غير محدد","order":4,"archiveStatus":"UNSPECIFIED","mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"},{"id":"file-contradictory-archived","sectionId":"sec-1","title":"متناقض مؤرشف","order":5,"archiveStatus":"UNARCHIVED","isArchived":true,"mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"},{"id":"file-string-is-archived","sectionId":"sec-1","title":"حقل نصي","order":6,"archiveStatus":"UNARCHIVED","isArchived":"false","mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"},{"id":"file-unpublished","sectionId":"sec-1","title":"غير منشور","order":7,"archiveStatus":"UNARCHIVED","isPublished":false,"mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"},{"id":"file-wrong-section","sectionId":"sec-other","title":"قسم آخر","order":8,"archiveStatus":"UNARCHIVED","mimeType":"application/pdf","activeVersionId":"v1","activeVersionNumber":1,"fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256"}"""
                } else {
                    ""
                }
                sendJson(exchange, 200, """{"items":[$validItem$extraItems]}""")
            }

            method == "POST" && path == "/api/admin/study-files/file-1/versions" -> {
                if (authHeader != "Bearer admin-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Unauthorized"}}""")
                    return
                }
                sendJson(
                    exchange,
                    201,
                    """{"version":{"id":"v1","fileId":"file-1","versionNumber":1,"versionLabel":"1.0","versionNotes":"النسخة المعتمدة","isPublished":false,"isArchived":false,"binaryVerificationStatus":"NONE"}}"""
                )
            }

            method == "POST" && path == "/api/admin/study-files/file-1/versions/v1/upload-slot" -> {
                if (authHeader != "Bearer admin-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Unauthorized"}}""")
                    return
                }
                val sessionId = "11111111-1111-4111-8111-111111111111"
                currentSessionId = sessionId
                versionVerificationStatus = "PENDING"
                val stagingKey = "uploads-staging/file-1/v1/$sessionId.pdf"
                sendJson(
                    exchange,
                    201,
                    """{"fileId":"file-1","versionId":"v1","uploadSessionId":"$sessionId","stagingKey":"$stagingKey","uploadUrl":"$baseUrl/r2-staging/$stagingKey","requiredHeaders":{"Content-Type":"application/pdf"},"expiresAtEpochMillis":1800000000000}"""
                )
            }

            method == "PUT" && path.startsWith("/r2-staging/") -> {
                uploadedStagingBytes = exchange.requestBody.use { it.readBytes() }
                val scriptedR2 = scriptedR2PutError
                if (scriptedR2 != null) {
                    scriptedR2PutError = null
                    sendXml(exchange, scriptedR2.first, scriptedR2.second)
                    return
                }
                exchange.responseHeaders.set("ETag", "\"etag-staging-1\"")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }

            method == "POST" && path == "/api/admin/study-files/file-1/versions/v1/verify" -> {
                if (authHeader != "Bearer admin-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Unauthorized"}}""")
                    return
                }
                val scripted = scriptedAdminError
                if (scripted != null) {
                    scriptedAdminError = null
                    sendJson(exchange, scripted.first, """{"error":{"code":"${scripted.second}","message":"Scripted error"}}""")
                    return
                }
                val bytes = uploadedStagingBytes
                if (bytes == null || sha256Hex(bytes) != pdfSha256) {
                    sendJson(exchange, 422, """{"error":{"code":"SHA256_MISMATCH","message":"Mismatch"}}""")
                    return
                }
                val attemptId = "22222222-2222-4222-8222-222222222222"
                currentAttemptId = attemptId
                versionVerificationStatus = "VERIFIED"
                val finalKey = "study-files/file-1/versions/v1/${pdfSha256.take(16)}_${currentSessionId}_$attemptId.pdf"
                sendJson(
                    exchange,
                    200,
                    """{"fileId":"file-1","versionId":"v1","uploadSessionId":"$currentSessionId","attemptId":"$attemptId","r2ObjectKey":"$finalKey","fileSizeBytes":${bytes.size},"checksumSha256":"$pdfSha256"}"""
                )
            }

            method == "GET" && path == "/api/admin/study-files/file-tristate/versions" -> {
                if (authHeader != "Bearer admin-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Unauthorized"}}""")
                    return
                }
                sendJson(
                    exchange,
                    200,
                    """{"items":[
                        {"id":"v_unarchived","fileId":"file-tristate","versionNumber":1,"versionLabel":"1.0","isPublished":false,"isArchived":false,"archiveStatus":"UNARCHIVED"},
                        {"id":"v_archived","fileId":"file-tristate","versionNumber":2,"versionLabel":"2.0","isPublished":false,"isArchived":true,"archiveStatus":"ARCHIVED"},
                        {"id":"v_unspecified","fileId":"file-tristate","versionNumber":3,"versionLabel":"3.0","isPublished":false,"isArchived":null,"archiveStatus":"UNSPECIFIED"},
                        {"id":"v_invalid_archive_status","fileId":"file-tristate","versionNumber":4,"versionLabel":"4.0","isPublished":false,"isArchived":false,"archiveStatus":"BOGUS"},
                        {"id":"v_missing_archive_null","fileId":"file-tristate","versionNumber":5,"versionLabel":"5.0","isPublished":false,"isArchived":null},
                        {"id":"v_missing_archive_string","fileId":"file-tristate","versionNumber":6,"versionLabel":"6.0","isPublished":false,"isArchived":"false"},
                        {"id":"v_missing_archive_false","fileId":"file-tristate","versionNumber":7,"versionLabel":"7.0","isPublished":false,"isArchived":false},
                        {"id":"v_missing_archive_true","fileId":"file-tristate","versionNumber":8,"versionLabel":"8.0","isPublished":false,"isArchived":true},
                        {"id":"v_contradict_unarchived_true","fileId":"file-tristate","versionNumber":9,"versionLabel":"9.0","isPublished":false,"isArchived":true,"archiveStatus":"UNARCHIVED"},
                        {"id":"v_contradict_archived_false","fileId":"file-tristate","versionNumber":10,"versionLabel":"10.0","isPublished":false,"isArchived":false,"archiveStatus":"ARCHIVED"},
                        {"id":"v_unarchived_null_bool","fileId":"file-tristate","versionNumber":11,"versionLabel":"11.0","isPublished":false,"isArchived":null,"archiveStatus":"UNARCHIVED"},
                        {"id":"v_archived_null_bool","fileId":"file-tristate","versionNumber":12,"versionLabel":"12.0","isPublished":false,"isArchived":null,"archiveStatus":"ARCHIVED"},
                        {"id":"v_unarchived_string_bool","fileId":"file-tristate","versionNumber":13,"versionLabel":"13.0","isPublished":false,"isArchived":"false","archiveStatus":"UNARCHIVED"}
                    ]}"""
                )
            }

            method == "GET" && path == "/api/admin/study-files/file-1/versions" -> {
                if (authHeader != "Bearer admin-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Unauthorized"}}""")
                    return
                }
                val proofFields = if (versionVerificationStatus == "VERIFIED") {
                    ""","fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256","verifiedUploadSessionId":"$currentSessionId","verifiedAttemptId":"$currentAttemptId""""
                } else {
                    ""
                }
                val draftV2Item = if (includeDraftV2InVersionsList) {
                    """,{"id":"v2","fileId":"file-1","versionNumber":2,"versionLabel":"2.0","isPublished":false,"isArchived":false,"archiveStatus":"UNARCHIVED","binaryVerificationStatus":"VERIFIED","fileSizeBytes":${pdfPayload.size},"checksumSha256":"$pdfSha256","verifiedUploadSessionId":"$currentSessionId","verifiedAttemptId":"$currentAttemptId"}"""
                } else {
                    ""
                }
                sendJson(
                    exchange,
                    200,
                    """{"items":[{"id":"v1","fileId":"file-1","versionNumber":1,"versionLabel":"1.0","isPublished":$versionPublished,"isArchived":false,"archiveStatus":"UNARCHIVED","binaryVerificationStatus":"$versionVerificationStatus"$proofFields}$draftV2Item]}"""
                )
            }

            method == "POST" && path == "/api/admin/study-files/file-1/versions/v1/publish" -> {
                if (authHeader != "Bearer admin-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Unauthorized"}}""")
                    return
                }
                val scripted = scriptedAdminError
                if (scripted != null) {
                    scriptedAdminError = null
                    sendJson(exchange, scripted.first, """{"error":{"code":"${scripted.second}","message":"Scripted error"}}""")
                    return
                }
                versionPublished = true
                lastPublishedVersionId = "v1"
                sendJson(
                    exchange,
                    200,
                    """{"status":"PUBLISHED","fileId":"file-1","versionId":"v1","versionNumber":1,"versionLabel":"1.0"}"""
                )
            }

            method == "POST" && path == "/api/admin/study-files/file-1/versions/v2/publish" -> {
                if (authHeader != "Bearer admin-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Unauthorized"}}""")
                    return
                }
                lastPublishedVersionId = "v2"
                sendJson(
                    exchange,
                    200,
                    """{"status":"PUBLISHED","fileId":"file-1","versionId":"v2","versionNumber":2,"versionLabel":"2.0"}"""
                )
            }

            method == "GET" && path == "/api/study-files/file-1/versions/v1/stream" -> {
                sendPdf(exchange, pdfPayload, pdfSha256)
            }

            method == "GET" && (path == "/api/study-files/file-1/versions/v1/download" ||
                path == "/api/study-files/file-1/versions/v2/download") -> {
                lastObservedDownloadAuth = authHeader
                if (authHeader != "Bearer student-jwt") {
                    sendJson(exchange, 401, """{"error":{"code":"UNAUTHENTICATED","message":"Student auth required"}}""")
                    return
                }
                val bytes = if (serveCorruptedDownloadBytes) {
                    "%PDF-1.7\nCORRUPTED_BYTES\n%%EOF".toByteArray(Charsets.UTF_8)
                } else {
                    pdfPayload
                }
                sendPdf(exchange, bytes, pdfSha256)
            }

            else -> {
                sendJson(exchange, 404, """{"error":{"code":"NOT_FOUND","message":"Not found: $path"}}""")
            }
        }
    }

    private fun sendJson(exchange: LoopbackHttpExchange, status: Int, jsonBody: String) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun sendXml(exchange: LoopbackHttpExchange, status: Int, xmlBody: String) {
        val bytes = xmlBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/xml; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun sendPdf(exchange: LoopbackHttpExchange, bytes: ByteArray, sha256Header: String) {
        exchange.responseHeaders.set("Content-Type", "application/pdf")
        exchange.responseHeaders.set("X-Content-Sha256", sha256Header)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private open class NoOpCatalogMetadataRemoteDataSource : AdminCatalogRemoteDataSource {
        override suspend fun fetchAllBranches(): List<StudyBranch> = emptyList()
        override suspend fun createBranch(customId: String?, name: String, order: Int, isPublished: Boolean, actorUid: String): StudyBranch = error("unused")
        override suspend fun updateBranch(branchId: String, name: String, order: Int, actorUid: String) = Unit
        override suspend fun setBranchPublished(branchId: String, isPublished: Boolean, actorUid: String) = Unit
        override suspend fun fetchAllSubjects(branchId: String): List<StudySubject> = emptyList()
        override suspend fun createSubject(customId: String?, branchId: String, name: String, order: Int, isPublished: Boolean, actorUid: String): StudySubject = error("unused")
        override suspend fun updateSubject(subjectId: String, name: String, order: Int, actorUid: String) = Unit
        override suspend fun setSubjectPublished(subjectId: String, isPublished: Boolean, actorUid: String) = Unit
        override suspend fun fetchAllSections(subjectId: String): List<StudySection> = emptyList()
        override suspend fun createSection(customId: String?, subjectId: String, name: String, order: Int, isPublished: Boolean, actorUid: String): StudySection = error("unused")
        override suspend fun updateSection(sectionId: String, name: String, order: Int, actorUid: String) = Unit
        override suspend fun setSectionPublished(sectionId: String, isPublished: Boolean, actorUid: String) = Unit
        override suspend fun fetchStudyFilesForSection(sectionId: String): List<StudyFileMetadata> = emptyList()
        override suspend fun createStudyFileMetadata(customId: String?, sectionId: String, title: String, description: String?, order: Int, isPublished: Boolean, actorUid: String): StudyFileMetadata = error("unused")
        override suspend fun updateStudyFileMetadata(fileId: String, sectionId: String, title: String, description: String?, order: Int, actorUid: String) = Unit
        override suspend fun setStudyFilePublished(fileId: String, isPublished: Boolean, actorUid: String) = Unit
        override suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean, actorUid: String) = Unit
        override suspend fun fetchStudyFileVersions(fileId: String): List<StudyFileVersionMetadata> = emptyList()
        override suspend fun createStudyFileVersion(fileId: String, versionNumber: Int, versionLabel: String, versionNotes: String?, publishAsActive: Boolean, actorUid: String, customVersionId: String?): StudyFileVersionMetadata = error("unused")
        override suspend fun updateStudyFileVersionMetadata(fileId: String, versionId: String, versionLabel: String, versionNotes: String?, actorUid: String) = Unit
        override suspend fun publishStudyFileVersion(fileId: String, versionId: String, actorUid: String, verifiedProof: VerifiedPdfBinaryProof) = Unit
        override suspend fun reorderBranches(firstId: String, firstNewOrder: Int, secondId: String, secondNewOrder: Int, actorUid: String) = Unit
        override suspend fun reorderSubjects(firstId: String, firstNewOrder: Int, secondId: String, secondNewOrder: Int, actorUid: String) = Unit
        override suspend fun reorderSections(firstId: String, firstNewOrder: Int, secondId: String, secondNewOrder: Int, actorUid: String) = Unit
        override suspend fun reorderStudyFiles(firstId: String, firstNewOrder: Int, secondId: String, secondNewOrder: Int, actorUid: String) = Unit
    }
}
