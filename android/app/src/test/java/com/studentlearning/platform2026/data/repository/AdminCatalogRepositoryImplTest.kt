package com.studentlearning.platform2026.data.repository

import com.google.firebase.firestore.FirebaseFirestoreException
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogMapper
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.data.pdf.BrokeredPdfVersionStorageDataSource
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfVersionStorageBroker
import com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminCatalogRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    private fun fakeVersionStorageBroker(
        statusProvider: () -> PdfBrokerConfigurationStatus = {
            PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
        },
        verifyProvider: (fileId: String, versionId: String) -> AppResult<VerifiedPdfBinaryProof> = { fileId, versionId ->
            if (statusProvider() != PdfBrokerConfigurationStatus.CONFIGURED) {
                AppResult.Error(
                    message = "Cloudflare R2 verification broker is not configured.",
                    cause = PdfLoadException(PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED)
                )
            } else {
                AppResult.Success(
                    VerifiedPdfBinaryProof(
                        fileId = fileId,
                        versionId = versionId,
                        r2ObjectKey = "study-files/$fileId/versions/$versionId/document.pdf",
                        fileSizeBytes = 12_345L,
                        checksumSha256 = "a".repeat(64)
                    )
                )
            }
        }
    ): PdfVersionStorageBroker = object : PdfVersionStorageBroker {
        override fun getBrokerStatus(): PdfBrokerConfigurationStatus = statusProvider()

        override suspend fun requestVersionUploadSlot(
            fileId: String,
            versionId: String,
            fileSizeBytes: Long,
            checksumSha256: String
        ): AppResult<PdfVersionUploadSlot> {
            if (getBrokerStatus() != PdfBrokerConfigurationStatus.CONFIGURED) {
                return AppResult.Error(
                    message = "Cloudflare R2 upload broker is not configured.",
                    cause = AdminException(
                        reason = AdminFailureReason.VALIDATION_ERROR,
                        validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING
                    )
                )
            }
            return AppResult.Success(
                PdfVersionUploadSlot(
                    fileId = fileId,
                    versionId = versionId,
                    r2ObjectKey = "uploads-staging/$fileId/$versionId/test.pdf",
                    signedUploadUrl = "https://example.invalid/upload/$fileId/$versionId"
                )
            )
        }

        override suspend fun verifyUploadedPdfBinary(
            fileId: String,
            versionId: String
        ): AppResult<VerifiedPdfBinaryProof> = verifyProvider(fileId, versionId)
    }

    @Test
    fun `blocks unauthenticated users before touching Firestore remote data source`() = runTest {
        val authRepository = FakeAuthRepository(AdminSession(isSignedIn = false))
        val remote = RecordingAdminRemoteDataSource()
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val result = repository.createBranch(null, "السادس العلمي", 1, isPublished = false)

        assertTrue(result is AppResult.Error)
        assertEquals(AdminFailureReason.UNAUTHENTICATED, ((result as AppResult.Error).cause as AdminException).reason)
        assertEquals(0, remote.writeCallCount)
    }

    @Test
    fun `blocks signed-in non-admin users from admin reads and writes`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val readResult = repository.getAllBranches()
        val writeResult = repository.createSubject(null, "scientific", "الفيزياء", 1, isPublished = false)

        assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, ((readResult as AppResult.Error).cause as AdminException).reason)
        assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, ((writeResult as AppResult.Error).cause as AdminException).reason)
        assertEquals(0, remote.writeCallCount)
    }

    @Test
    fun `blocks admin operations when token claims are not confirmed current`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = false
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val result = repository.updateBranch("scientific", "السادس العلمي", 1)

        assertTrue(result is AppResult.Error)
        assertEquals(
            AdminFailureReason.SESSION_VERIFICATION_REQUIRED,
            ((result as AppResult.Error).cause as AdminException).reason
        )
        assertEquals(0, remote.writeCallCount)
    }

    @Test
    fun `allows owner role to create section and maps Firestore PERMISSION_DENIED cleanly`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource(failWriteWithPermissionDenied = true)
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val result = repository.createSection(null, "physics", "الفصل الأول", 1, isPublished = false)

        assertEquals(1, remote.writeCallCount)
        assertTrue(result is AppResult.Error)
        assertEquals(AdminFailureReason.PERMISSION_DENIED, ((result as AppResult.Error).cause as AdminException).reason)
    }

    @Test
    fun `maps Firestore PERMISSION_DENIED on admin reads cleanly`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource(failReadWithPermissionDenied = true)
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val result = repository.getAllBranches()

        assertTrue(result is AppResult.Error)
        assertEquals(AdminFailureReason.PERMISSION_DENIED, ((result as AppResult.Error).cause as AdminException).reason)
    }

    @Test
    fun `allows admin role to archive PDF metadata with actor UID`() = runTest {
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
        val remote = RecordingAdminRemoteDataSource()
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val result = repository.setStudyFileArchived("file-1", isArchived = true)

        assertTrue(result is AppResult.Success)
        assertEquals("admin-uid", remote.lastActorUid)
    }

    @Test
    fun `updateStudyFileMetadata passes explicit sectionId distinct from fileId and rejects blank sectionId`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val invalidResult = repository.updateStudyFileMetadata(
            fileId = "file-99",
            sectionId = "  ",
            title = "ملزمة محدثة",
            description = "وصف",
            order = 1
        )
        assertTrue(invalidResult is AppResult.Error)
        val invalidEx = (invalidResult as AppResult.Error).cause as AdminException
        assertEquals(AdminFailureReason.VALIDATION_ERROR, invalidEx.reason)
        assertEquals(AdminValidationError.EMPTY_PARENT_REFERENCE, invalidEx.validationError)
        assertEquals(0, remote.writeCallCount)

        val validResult = repository.updateStudyFileMetadata(
            fileId = "file-99",
            sectionId = "sec-12",
            title = "  ملزمة الفصل الأول  ",
            description = "  شرح شامل  ",
            order = 2
        )
        assertTrue(validResult is AppResult.Success)
        assertEquals(1, remote.writeCallCount)
        assertEquals("file-99", remote.lastUpdatedFileId)
        assertEquals("sec-12", remote.lastUpdatedFileSectionId)
        assertEquals("owner-uid", remote.lastActorUid)
    }

    @Test
    fun `reorderBranches is owner-only while reorderStudyFiles allows admin with actor UID`() = runTest {
        val adminAuth = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "admin-uid",
                email = "admin@example.com",
                role = AdminRole.ADMIN,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        val adminRepo = AdminCatalogRepositoryImpl(
            authRepository = adminAuth,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val adminBranchReorder = adminRepo.reorderBranches("b2", 1, "b1", 2)
        assertTrue(adminBranchReorder is AppResult.Error)
        assertEquals(
            AdminFailureReason.INSUFFICIENT_ROLE,
            ((adminBranchReorder as AppResult.Error).cause as AdminException).reason
        )

        val adminSubjectUnpublish = adminRepo.setSubjectPublished("s1", isPublished = false)
        assertTrue(adminSubjectUnpublish is AppResult.Error)
        assertEquals(
            AdminFailureReason.INSUFFICIENT_ROLE,
            ((adminSubjectUnpublish as AppResult.Error).cause as AdminException).reason
        )

        val adminSubjectPublish = adminRepo.setSubjectPublished("s1", isPublished = true)
        val fileResult = adminRepo.reorderStudyFiles("f2", 1, "f1", 2)
        assertTrue(adminSubjectPublish is AppResult.Success)
        assertTrue(fileResult is AppResult.Success)

        val ownerAuth = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val ownerRepo = AdminCatalogRepositoryImpl(
            authRepository = ownerAuth,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )
        val ownerBranchResult = ownerRepo.reorderBranches("b2", 1, "b1", 2)
        assertTrue(ownerBranchResult is AppResult.Success)
        assertEquals(listOf("b2" to 1, "b1" to 2), remote.lastReorderedPair)
        assertEquals("owner-uid", remote.lastActorUid)
    }

    @Test
    fun `blocks immediate version publish when PDF binary upload broker is unconfigured`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        val unconfiguredBroker = fakeVersionStorageBroker(
            statusProvider = {
                PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
            },
            verifyProvider = { _, _ ->
                AppResult.Error(
                    message = "Cloudflare R2 verification broker is not configured.",
                    cause = PdfLoadException(PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED)
                )
            }
        )
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = unconfiguredBroker
        )

        val immediateCreateResult = repository.createStudyFileVersion(
            fileId = "file-1",
            versionNumber = 2,
            versionLabel = "2.0",
            versionNotes = "تحديث",
            publishAsActive = true,
            customVersionId = "v2"
        )
        val publishExistingResult = repository.publishStudyFileVersion(
            fileId = "file-1",
            versionId = "v2"
        )

        assertEquals(0, remote.writeCallCount)
        assertEquals(
            AdminValidationError.VERSION_PDF_BINARY_MISSING,
            ((immediateCreateResult as AppResult.Error).cause as AdminException).validationError
        )
        assertEquals(
            AdminValidationError.VERSION_PDF_BINARY_MISSING,
            ((publishExistingResult as AppResult.Error).cause as AdminException).validationError
        )
    }

    @Test
    fun `allows draft version creation when broker is pending and delegates publish only when broker configured`() =
        runTest {
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
            var currentBrokerStatus = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
            val broker = fakeVersionStorageBroker(
                statusProvider = { currentBrokerStatus },
                verifyProvider = { fileId, versionId ->
                    if (currentBrokerStatus != PdfBrokerConfigurationStatus.CONFIGURED) {
                        AppResult.Error(
                            message = "Cloudflare R2 verification broker is not configured.",
                            cause = PdfLoadException(PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED)
                        )
                    } else {
                        AppResult.Success(
                            VerifiedPdfBinaryProof(
                                fileId = fileId,
                                versionId = versionId,
                                r2ObjectKey = "study-files/$fileId/versions/$versionId/document.pdf",
                                fileSizeBytes = 12_345L,
                                checksumSha256 = "a".repeat(64)
                            )
                        )
                    }
                }
            )
            val remote = RecordingAdminRemoteDataSource()
            val repository = AdminCatalogRepositoryImpl(
                authRepository = authRepository,
                remoteDataSource = remote,
                dispatcherProvider = testDispatchers,
                pdfVersionStorageBroker = broker
            )

            // Draft creation succeeds while R2 broker is still pending.
            val draftResult = repository.createStudyFileVersion(
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "  1.0  ",
                versionNotes = "   ",
                publishAsActive = false,
                customVersionId = "v1"
            )
            assertTrue(draftResult is AppResult.Success)
            assertEquals("v1", (draftResult as AppResult.Success).data.id)
            assertEquals("1.0", remote.lastCreatedVersionLabel)
            assertEquals("admin-uid", remote.lastActorUid)

            // Updating metadata delegates cleanly with trimmed label/notes.
            val updateResult = repository.updateStudyFileVersionMetadata(
                fileId = "file-1",
                versionId = "v1",
                versionLabel = " 1.1 ",
                versionNotes = " ملاحظات "
            )
            assertTrue(updateResult is AppResult.Success)
            assertEquals("1.1", remote.lastCreatedVersionLabel)

            // Publishing succeeds at repository layer once broker is CONFIGURED.
            currentBrokerStatus = PdfBrokerConfigurationStatus.CONFIGURED
            val publishResult = repository.publishStudyFileVersion(fileId = "file-1", versionId = "v1")
            assertTrue(publishResult is AppResult.Success)
            assertEquals("file-1" to "v1", remote.lastPublishedVersion)
        }

    @Test
    fun `blocks non-admin and stale-claim sessions on version reads and writes`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val readResult = repository.getStudyFileVersions("file-1")
        val createResult = repository.createStudyFileVersion(
            fileId = "file-1",
            versionNumber = 1,
            versionLabel = "1.0",
            versionNotes = null,
            publishAsActive = false
        )
        assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, ((readResult as AppResult.Error).cause as AdminException).reason)
        assertEquals(AdminFailureReason.INSUFFICIENT_ROLE, ((createResult as AppResult.Error).cause as AdminException).reason)
        assertEquals(0, remote.writeCallCount)
    }

    @Test
    fun `blocks republishing archived parent study file and allows after unarchiving`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val preservedActiveVersion = StudyFileVersionMetadata(
            id = "v1",
            fileId = "file-archived",
            versionNumber = 1,
            versionLabel = "1.0",
            r2ObjectKey = "study-files/file-archived/versions/v1/document.pdf",
            fileSizeBytes = 12_345L,
            checksumSha256 = "a".repeat(64),
            isPublished = true,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
        val remote = RecordingAdminRemoteDataSource(
            initialFileArchiveStatus = ArchiveStatus.ARCHIVED,
            initialActiveVersionId = "v1",
            initialActiveVersionNumber = 1,
            initialActiveVersion = preservedActiveVersion
        )
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker(
                statusProvider = { PdfBrokerConfigurationStatus.CONFIGURED },
                verifyProvider = { fileId, versionId ->
                    AppResult.Success(
                        VerifiedPdfBinaryProof(
                            fileId = fileId,
                            versionId = versionId,
                            r2ObjectKey = "study-files/$fileId/versions/$versionId/document.pdf",
                            fileSizeBytes = 12_345L,
                            checksumSha256 = "a".repeat(64)
                        )
                    )
                }
            )
        )

        val blockedPublish = repository.setStudyFilePublished("file-archived", isPublished = true)
        assertTrue(blockedPublish is AppResult.Error)
        val cause = (blockedPublish as AppResult.Error).cause as AdminException
        assertEquals(AdminFailureReason.VALIDATION_ERROR, cause.reason)
        assertEquals(AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION, cause.validationError)

        val unarchiveResult = repository.setStudyFileArchived("file-archived", isArchived = false)
        assertTrue(unarchiveResult is AppResult.Success)

        val publishAfterUnarchive = repository.setStudyFilePublished("file-archived", isPublished = true)
        assertTrue(publishAfterUnarchive is AppResult.Success)
        assertEquals(true, remote.lastFilePublished)
    }

    @Test
    fun `changing brokerEndpointBaseUrl to arbitrary https URL never unlocks CONFIGURED or version publish`() =
        runTest {
            val authRepository = FakeAuthRepository(
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-uid",
                    email = "owner@example.com",
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val remote = RecordingAdminRemoteDataSource()
            val adapterWithHttpsUrl = BrokeredPdfVersionStorageDataSource(
                brokerEndpointBaseUrl = "https://fake-or-unverified.example.invalid"
            )
            assertEquals(
                PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION,
                adapterWithHttpsUrl.getBrokerStatus()
            )
            assertTrue(
                adapterWithHttpsUrl.verifyUploadedPdfBinary("file-1", "v1") is AppResult.Error
            )
            val repository = AdminCatalogRepositoryImpl(
                authRepository = authRepository,
                remoteDataSource = remote,
                dispatcherProvider = testDispatchers,
                pdfVersionStorageBroker = adapterWithHttpsUrl
            )

            val publishResult = repository.publishStudyFileVersion(fileId = "file-1", versionId = "v1")
            assertTrue(publishResult is AppResult.Error)
            assertEquals(
                AdminValidationError.VERSION_PDF_BINARY_MISSING,
                ((publishResult as AppResult.Error).cause as AdminException).validationError
            )
            assertEquals(0, remote.writeCallCount)

            // Draft creation remains completely operational even while broker is pending.
            val draftFile = repository.createStudyFileMetadata(
                customId = "file-1",
                sectionId = "sec-1",
                title = "ملزمة جديدة",
                description = null,
                order = 1,
                isPublished = false
            )
            assertTrue(draftFile is AppResult.Success)
            val draftVersion = repository.createStudyFileVersion(
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "1.0",
                versionNotes = null,
                publishAsActive = false,
                customVersionId = "v1"
            )
            assertTrue(draftVersion is AppResult.Success)
        }

    @Test
    fun `blocks version publish when server binary verification fails or returns invalid proof`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        var proofToReturn: AppResult<VerifiedPdfBinaryProof> = AppResult.Error(
            message = "Object missing in R2",
            cause = PdfLoadException(PdfFailureReason.FILE_NOT_FOUND)
        )
        val broker = fakeVersionStorageBroker(
            statusProvider = { PdfBrokerConfigurationStatus.CONFIGURED },
            verifyProvider = { _, _ -> proofToReturn }
        )
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = broker
        )

        val missingResult = repository.publishStudyFileVersion("file-1", "v1")
        assertTrue(missingResult is AppResult.Error)
        assertEquals(
            AdminValidationError.VERSION_PDF_BINARY_MISSING,
            ((missingResult as AppResult.Error).cause as AdminException).validationError
        )
        assertEquals(0, remote.writeCallCount)

        // Mismatched object key / invalid sha256 returned by broker must also be rejected before Firestore.
        proofToReturn = AppResult.Success(
            VerifiedPdfBinaryProof(
                fileId = "file-1",
                versionId = "v1",
                r2ObjectKey = "study-files/other-file/versions/v1/document.pdf",
                fileSizeBytes = 10_000L,
                checksumSha256 = "f".repeat(64)
            )
        )
        val mismatchedKeyResult = repository.publishStudyFileVersion("file-1", "v1")
        assertTrue(mismatchedKeyResult is AppResult.Error)
        assertEquals(0, remote.writeCallCount)
    }

    @Test
    fun `blocks creating a published parent study file without an active published version`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource()
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val blockedCreate = repository.createStudyFileMetadata(
            customId = "file-new",
            sectionId = "sec-1",
            title = "ملزمة",
            description = null,
            order = 1,
            isPublished = true
        )
        assertTrue(blockedCreate is AppResult.Error)
        assertEquals(
            AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
            ((blockedCreate as AppResult.Error).cause as AdminException).validationError
        )
        assertEquals(0, remote.writeCallCount)
    }

    @Test
    fun `archiving published study file sets isPublished false and restoring keeps it unpublished`() = runTest {
        val authRepository = FakeAuthRepository(
            AdminSession(
                isSignedIn = true,
                uid = "owner-uid",
                email = "owner@example.com",
                role = AdminRole.OWNER,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val remote = RecordingAdminRemoteDataSource(initialFileArchiveStatus = ArchiveStatus.UNARCHIVED).apply {
            lastFilePublished = true
        }
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker()
        )

        val archiveResult = repository.setStudyFileArchived("file-1", isArchived = true)
        assertTrue(archiveResult is AppResult.Success)
        assertEquals(ArchiveStatus.ARCHIVED, remote.fileArchiveStatus)
        assertEquals(false, remote.lastFilePublished)

        val restoreResult = repository.setStudyFileArchived("file-1", isArchived = false)
        assertTrue(restoreResult is AppResult.Success)
        assertEquals(ArchiveStatus.UNARCHIVED, remote.fileArchiveStatus)
        assertEquals(false, remote.lastFilePublished)
    }

    @Test
    fun `setStudyFileArchived rejects UNSPECIFIED document without calling remote archive mutation and preserves CancellationException`() =
        runTest {
            val authRepository = FakeAuthRepository(
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-uid",
                    email = "owner@example.com",
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val remote = RecordingAdminRemoteDataSource(
                initialFileArchiveStatus = ArchiveStatus.UNSPECIFIED
            )
            val repository = AdminCatalogRepositoryImpl(
                authRepository = authRepository,
                remoteDataSource = remote,
                dispatcherProvider = testDispatchers,
                pdfVersionStorageBroker = fakeVersionStorageBroker()
            )

            val blockedArchive = repository.setStudyFileArchived("file-1", isArchived = true)
            assertTrue(blockedArchive is AppResult.Error)
            val archiveEx = (blockedArchive as AppResult.Error).cause as AdminException
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, archiveEx.validationError)
            assertEquals(0, remote.writeCallCount)

            val blockedRestore = repository.setStudyFileArchived("file-1", isArchived = false)
            assertTrue(blockedRestore is AppResult.Error)
            val restoreEx = (blockedRestore as AppResult.Error).cause as AdminException
            assertEquals(AdminValidationError.ARCHIVE_STATE_UNSPECIFIED, restoreEx.validationError)
            assertEquals(0, remote.writeCallCount)

            remote.cancelArchiveCall = true
            remote.fileArchiveStatus = ArchiveStatus.UNARCHIVED
            var caughtCancellation: Throwable? = null
            try {
                repository.setStudyFileArchived("file-1", isArchived = true)
            } catch (ex: Throwable) {
                caughtCancellation = ex
            }
            assertTrue(caughtCancellation is CancellationException)
        }

    @Test
    fun `setStudyFilePublished via remote validates active version number and PDF proof while allowing unpublish`() =
        runTest {
            val authRepository = FakeAuthRepository(
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-uid",
                    email = "owner@example.com",
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val validVersion = StudyFileVersionMetadata(
                id = "v1",
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "1.0",
                r2ObjectKey = "study-files/file-1/versions/v1/document.pdf",
                fileSizeBytes = 4096L,
                checksumSha256 = "d".repeat(64),
                isPublished = true,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
            val remote = RecordingAdminRemoteDataSource(
                initialActiveVersionId = "v1",
                initialActiveVersionNumber = 0,
                initialActiveVersion = validVersion
            )
            val repository = AdminCatalogRepositoryImpl(
                authRepository = authRepository,
                remoteDataSource = remote,
                dispatcherProvider = testDispatchers,
                pdfVersionStorageBroker = fakeVersionStorageBroker()
            )

            // activeVersionNumber = 0 must be rejected just like production FirestoreAdminCatalogDataSource.
            val zeroNumPublish = repository.setStudyFilePublished("file-1", isPublished = true)
            assertTrue(zeroNumPublish is AppResult.Error)
            assertEquals(
                AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                ((zeroNumPublish as AppResult.Error).cause as AdminException).validationError
            )

            // Missing checksum proof must be rejected on publish.
            remote.activeVersionNumber = 1
            remote.activeVersion = validVersion.copy(checksumSha256 = null)
            val missingProofPublish = repository.setStudyFilePublished("file-1", isPublished = true)
            assertTrue(missingProofPublish is AppResult.Error)
            assertEquals(
                AdminValidationError.VERSION_PDF_BINARY_MISSING,
                ((missingProofPublish as AppResult.Error).cause as AdminException).validationError
            )

            // Unpublishing remains allowed even when version metadata is incomplete.
            remote.activeVersionId = null
            remote.activeVersionNumber = null
            remote.activeVersion = null
            val unpublishResult = repository.setStudyFilePublished("file-1", isPublished = false)
            assertTrue(unpublishResult is AppResult.Success)
            assertEquals(false, remote.lastFilePublished)
        }

    @Test
    fun `uploadAndVerifyStudyFileVersionPdf executes slot put verify pipeline and validates returned checksum`() =
        runTest {
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
            val remote = RecordingAdminRemoteDataSource()
            val validSha = "e".repeat(64)
            var putCalled = false
            val customBroker = object : com.studentlearning.platform2026.domain.repository.PdfVersionStorageBroker {
                override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                override suspend fun requestVersionUploadSlot(
                    fileId: String,
                    versionId: String,
                    fileSizeBytes: Long,
                    checksumSha256: String
                ) = AppResult.Success(
                    com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot(
                        fileId = fileId,
                        versionId = versionId,
                        r2ObjectKey = "uploads-staging/$fileId/$versionId/sess-1.pdf",
                        signedUploadUrl = "https://r2.example.invalid/put",
                        uploadSessionId = "sess-1"
                    )
                )

                override suspend fun uploadPdfToSignedSlot(
                    slot: com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot,
                    binarySource: com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
                ): AppResult<Unit> {
                    putCalled = true
                    return AppResult.Success(Unit)
                }

                override suspend fun verifyUploadedPdfBinary(
                    fileId: String,
                    versionId: String
                ) = verifyUploadedPdfBinary(
                    fileId = fileId,
                    versionId = versionId,
                    uploadSessionId = null
                )

                override suspend fun verifyUploadedPdfBinary(
                    fileId: String,
                    versionId: String,
                    uploadSessionId: String?
                ) = AppResult.Success(
                    com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof(
                        fileId = fileId,
                        versionId = versionId,
                        r2ObjectKey = "study-files/$fileId/versions/$versionId/proof.pdf",
                        fileSizeBytes = 1024L,
                        checksumSha256 = validSha,
                        uploadSessionId = uploadSessionId
                    )
                )
            }
            val repository = AdminCatalogRepositoryImpl(
                authRepository = authRepository,
                remoteDataSource = remote,
                dispatcherProvider = testDispatchers,
                pdfVersionStorageBroker = customBroker
            )
            val binarySource = com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource(
                sizeBytes = 1024L,
                sha256Hex = validSha,
                displayName = "lesson.pdf",
                openInputStream = { "%PDF-1.7\n".toByteArray().inputStream() }
            )

            val result = repository.uploadAndVerifyStudyFileVersionPdf("file-1", "v1", binarySource)
            assertTrue(result is AppResult.Success)
            assertTrue(putCalled)
            assertEquals(validSha, (result as AppResult.Success).data.checksumSha256)

            val mismatchResult = repository.uploadAndVerifyStudyFileVersionPdf(
                "file-1",
                "v1",
                binarySource.copy(sha256Hex = "f".repeat(64))
            )
            assertTrue(mismatchResult is AppResult.Error)
            assertEquals(
                AdminValidationError.VERSION_PDF_BINARY_MISSING,
                ((mismatchResult as AppResult.Error).cause as AdminException).validationError
            )
        }

    @Test
    fun `publishStudyFileVersion preserves original AdminException from verifyUploadedPdfBinary without masking`() =
        runTest {
            val authRepository = FakeAuthRepository(
                AdminSession(
                    isSignedIn = true,
                    uid = "owner-uid",
                    email = "owner@example.com",
                    role = AdminRole.OWNER,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val remote = RecordingAdminRemoteDataSource()
            var nextVerifyError: AppResult<VerifiedPdfBinaryProof> = AppResult.Error(
                message = "Initial",
                cause = AdminException(AdminFailureReason.UNAUTHENTICATED, message = "Expired token")
            )
            val broker = fakeVersionStorageBroker(
                statusProvider = { PdfBrokerConfigurationStatus.CONFIGURED },
                verifyProvider = { _, _ -> nextVerifyError }
            )
            val repository = AdminCatalogRepositoryImpl(
                authRepository = authRepository,
                remoteDataSource = remote,
                dispatcherProvider = testDispatchers,
                pdfVersionStorageBroker = broker
            )

            val preservedCases = listOf(
                AdminException(AdminFailureReason.UNAUTHENTICATED, message = "Token revoked"),
                AdminException(AdminFailureReason.INSUFFICIENT_ROLE, message = "Not admin"),
                AdminException(AdminFailureReason.NETWORK_UNAVAILABLE, message = "Offline"),
                AdminException(
                    AdminFailureReason.VALIDATION_ERROR,
                    AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION
                ),
                AdminException(
                    AdminFailureReason.VALIDATION_ERROR,
                    AdminValidationError.R2_STORAGE_NOT_CONFIGURED
                ),
                AdminException(
                    AdminFailureReason.VALIDATION_ERROR,
                    AdminValidationError.VERSION_UPLOAD_SESSION_EXPIRED
                ),
                AdminException(
                    AdminFailureReason.VALIDATION_ERROR,
                    AdminValidationError.VERSION_PDF_BINARY_MISSING
                )
            )

            for (expectedEx in preservedCases) {
                nextVerifyError = AppResult.Error(
                    message = expectedEx.message ?: "Error",
                    cause = expectedEx
                )
                val result = repository.publishStudyFileVersion("file-1", "v1")
                assertTrue(result is AppResult.Error)
                val actualEx = (result as AppResult.Error).cause as AdminException
                assertEquals(expectedEx.reason, actualEx.reason)
                assertEquals(expectedEx.validationError, actualEx.validationError)
            }

            // Non-AdminException fallback continues mapping to VERSION_PDF_BINARY_MISSING.
            nextVerifyError = AppResult.Error(
                message = "Raw IO issue",
                cause = PdfLoadException(PdfFailureReason.FILE_NOT_FOUND)
            )
            val fallbackResult = repository.publishStudyFileVersion("file-1", "v1")
            assertTrue(fallbackResult is AppResult.Error)
            val fallbackEx = (fallbackResult as AppResult.Error).cause as AdminException
            assertEquals(AdminFailureReason.VALIDATION_ERROR, fallbackEx.reason)
            assertEquals(AdminValidationError.VERSION_PDF_BINARY_MISSING, fallbackEx.validationError)
            assertEquals(0, remote.writeCallCount)
        }

    private class RecordingAdminRemoteDataSource(
        private val failWriteWithPermissionDenied: Boolean = false,
        private val failReadWithPermissionDenied: Boolean = false,
        private val initialFileExists: Boolean = true,
        initialFileArchiveStatus: ArchiveStatus = ArchiveStatus.UNARCHIVED,
        initialActiveVersionId: String? = null,
        initialActiveVersionNumber: Int? = null,
        initialActiveVersion: StudyFileVersionMetadata? = null
    ) : AdminCatalogRemoteDataSource {
        var writeCallCount = 0
        var cancelArchiveCall = false
        var lastActorUid: String? = null
        var lastUpdatedFileId: String? = null
        var lastUpdatedFileSectionId: String? = null
        var lastReorderedPair: List<Pair<String, Int>>? = null
        var lastCreatedVersionLabel: String? = null
        var lastPublishedVersion: Pair<String, String>? = null
        var fileArchiveStatus: ArchiveStatus = initialFileArchiveStatus
        var lastFilePublished: Boolean? = null
        var activeVersionId: String? = initialActiveVersionId
        var activeVersionNumber: Int? = initialActiveVersionNumber
        var activeVersion: StudyFileVersionMetadata? = initialActiveVersion

        override suspend fun fetchAllBranches(): List<StudyBranch> {
            if (failReadWithPermissionDenied) {
                throw FirebaseFirestoreException(
                    "Rules block admin reads",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED
                )
            }
            return emptyList()
        }

        override suspend fun createBranch(
            customId: String?,
            name: String,
            order: Int,
            isPublished: Boolean,
            actorUid: String
        ): StudyBranch {
            writeCallCount++
            lastActorUid = actorUid
            return StudyBranch(customId ?: "b1", name, order, isPublished)
        }

        override suspend fun updateBranch(branchId: String, name: String, order: Int, actorUid: String) {
            writeCallCount++
            lastActorUid = actorUid
        }

        override suspend fun setBranchPublished(branchId: String, isPublished: Boolean, actorUid: String) {
            writeCallCount++
            lastActorUid = actorUid
        }

        override suspend fun fetchAllSubjects(branchId: String): List<StudySubject> = emptyList()

        override suspend fun createSubject(
            customId: String?,
            branchId: String,
            name: String,
            order: Int,
            isPublished: Boolean,
            actorUid: String
        ): StudySubject {
            writeCallCount++
            lastActorUid = actorUid
            return StudySubject(customId ?: "s1", branchId, name, order, isPublished)
        }

        override suspend fun updateSubject(subjectId: String, name: String, order: Int, actorUid: String) {
            writeCallCount++
        }

        override suspend fun setSubjectPublished(subjectId: String, isPublished: Boolean, actorUid: String) {
            writeCallCount++
        }

        override suspend fun fetchAllSections(subjectId: String): List<StudySection> = emptyList()

        override suspend fun createSection(
            customId: String?,
            subjectId: String,
            name: String,
            order: Int,
            isPublished: Boolean,
            actorUid: String
        ): StudySection {
            writeCallCount++
            lastActorUid = actorUid
            if (failWriteWithPermissionDenied) {
                throw FirebaseFirestoreException(
                    "Rules block admin writes",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED
                )
            }
            return StudySection(customId ?: "sec1", subjectId, name, order, isPublished)
        }

        override suspend fun updateSection(sectionId: String, name: String, order: Int, actorUid: String) {
            writeCallCount++
        }

        override suspend fun setSectionPublished(sectionId: String, isPublished: Boolean, actorUid: String) {
            writeCallCount++
        }

        override suspend fun fetchStudyFilesForSection(sectionId: String): List<StudyFileMetadata> = emptyList()

        override suspend fun fetchStudyFileMetadata(fileId: String): StudyFileMetadata? {
            if (!initialFileExists) return null
            return StudyFileMetadata(
                id = fileId,
                sectionId = "sec-1",
                title = "ملزمة",
                order = 1,
                isPublished = lastFilePublished ?: false,
                archiveStatus = fileArchiveStatus,
                activeVersionId = activeVersionId,
                activeVersionNumber = activeVersionNumber
            )
        }

        override suspend fun createStudyFileMetadata(
            customId: String?,
            sectionId: String,
            title: String,
            description: String?,
            order: Int,
            isPublished: Boolean,
            actorUid: String
        ): StudyFileMetadata {
            writeCallCount++
            lastActorUid = actorUid
            return StudyFileMetadata(
                id = customId ?: "f1",
                sectionId = sectionId,
                title = title,
                order = order,
                isPublished = isPublished,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
        }

        override suspend fun updateStudyFileMetadata(
            fileId: String,
            sectionId: String,
            title: String,
            description: String?,
            order: Int,
            actorUid: String
        ) {
            writeCallCount++
            lastActorUid = actorUid
            lastUpdatedFileId = fileId
            lastUpdatedFileSectionId = sectionId
        }

        override suspend fun setStudyFilePublished(fileId: String, isPublished: Boolean, actorUid: String) {
            writeCallCount++
            lastActorUid = actorUid
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = fileId,
                exists = initialFileExists,
                archiveStatus = fileArchiveStatus,
                targetPublished = isPublished,
                activeVersionId = activeVersionId,
                activeVersionNumber = activeVersionNumber,
                activeVersion = activeVersion
            )
            lastFilePublished = isPublished
        }

        override suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean, actorUid: String) {
            if (cancelArchiveCall) throw CancellationException("Cancelled")
            writeCallCount++
            lastActorUid = actorUid
            fileArchiveStatus = if (isArchived) ArchiveStatus.ARCHIVED else ArchiveStatus.UNARCHIVED
            lastFilePublished = false
        }

        override suspend fun fetchStudyFileVersions(fileId: String): List<StudyFileVersionMetadata> =
            emptyList()

        override suspend fun createStudyFileVersion(
            fileId: String,
            versionNumber: Int,
            versionLabel: String,
            versionNotes: String?,
            publishAsActive: Boolean,
            actorUid: String,
            customVersionId: String?
        ): StudyFileVersionMetadata {
            writeCallCount++
            lastActorUid = actorUid
            lastCreatedVersionLabel = versionLabel
            return StudyFileVersionMetadata(
                id = customVersionId ?: "ver_1",
                fileId = fileId,
                versionNumber = versionNumber,
                versionLabel = versionLabel,
                versionNotes = versionNotes,
                isPublished = publishAsActive,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
        }

        override suspend fun updateStudyFileVersionMetadata(
            fileId: String,
            versionId: String,
            versionLabel: String,
            versionNotes: String?,
            actorUid: String
        ) {
            writeCallCount++
            lastActorUid = actorUid
            lastCreatedVersionLabel = versionLabel
        }

        override suspend fun publishStudyFileVersion(
            fileId: String,
            versionId: String,
            actorUid: String,
            verifiedProof: VerifiedPdfBinaryProof
        ) {
            writeCallCount++
            lastActorUid = actorUid
            lastPublishedVersion = fileId to versionId
            val resolvedNumber = (activeVersionNumber ?: 0) + 1
            activeVersionId = versionId
            activeVersionNumber = resolvedNumber
            activeVersion = StudyFileVersionMetadata(
                id = versionId,
                fileId = fileId,
                versionNumber = resolvedNumber,
                versionLabel = "$resolvedNumber.0",
                r2ObjectKey = verifiedProof.r2ObjectKey,
                fileSizeBytes = verifiedProof.fileSizeBytes,
                checksumSha256 = verifiedProof.checksumSha256,
                isPublished = true,
                archiveStatus = ArchiveStatus.UNARCHIVED
            )
        }

        override suspend fun reorderBranches(
            firstId: String,
            firstNewOrder: Int,
            secondId: String,
            secondNewOrder: Int,
            actorUid: String
        ) {
            writeCallCount++
            lastActorUid = actorUid
            lastReorderedPair = listOf(firstId to firstNewOrder, secondId to secondNewOrder)
        }

        override suspend fun reorderSubjects(
            firstId: String,
            firstNewOrder: Int,
            secondId: String,
            secondNewOrder: Int,
            actorUid: String
        ) {
            writeCallCount++
            lastActorUid = actorUid
        }

        override suspend fun reorderSections(
            firstId: String,
            firstNewOrder: Int,
            secondId: String,
            secondNewOrder: Int,
            actorUid: String
        ) {
            writeCallCount++
            lastActorUid = actorUid
        }

        override suspend fun reorderStudyFiles(
            firstId: String,
            firstNewOrder: Int,
            secondId: String,
            secondNewOrder: Int,
            actorUid: String
        ) {
            writeCallCount++
            lastActorUid = actorUid
        }
    }
}
