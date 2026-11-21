package com.studentlearning.platform2026.data.repository

import com.google.firebase.firestore.FirebaseFirestoreException
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfVersionStorageBroker
import com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        }
    ): PdfVersionStorageBroker = object : PdfVersionStorageBroker {
        override fun getBrokerStatus(): PdfBrokerConfigurationStatus = statusProvider()

        override fun buildVersionObjectKey(fileId: String, versionId: String): String =
            "study-files/$fileId/versions/$versionId/document.pdf"

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
                    r2ObjectKey = buildVersionObjectKey(fileId, versionId),
                    signedUploadUrl = "https://example.invalid/upload/$fileId/$versionId"
                )
            )
        }
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
    fun `reorderBranches and reorderStudyFiles pass swapped orders atomically with actor UID`() = runTest {
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

        val branchResult = repository.reorderBranches("b2", 1, "b1", 2)
        val fileResult = repository.reorderStudyFiles("f2", 1, "f1", 2)

        assertTrue(branchResult is AppResult.Success)
        assertTrue(fileResult is AppResult.Success)
        assertEquals(2, remote.writeCallCount)
        assertEquals(listOf("b2" to 1, "b1" to 2), remote.lastReorderedPair)
        assertEquals("admin-uid", remote.lastActorUid)
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
        val unconfiguredBroker = fakeVersionStorageBroker {
            PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
        }
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
            val broker = fakeVersionStorageBroker { currentBrokerStatus }
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
        val remote = RecordingAdminRemoteDataSource(initialFileArchived = true)
        val repository = AdminCatalogRepositoryImpl(
            authRepository = authRepository,
            remoteDataSource = remote,
            dispatcherProvider = testDispatchers,
            pdfVersionStorageBroker = fakeVersionStorageBroker { PdfBrokerConfigurationStatus.CONFIGURED }
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

    private class RecordingAdminRemoteDataSource(
        private val failWriteWithPermissionDenied: Boolean = false,
        private val failReadWithPermissionDenied: Boolean = false,
        initialFileArchived: Boolean = false
    ) : AdminCatalogRemoteDataSource {
        var writeCallCount = 0
        var lastActorUid: String? = null
        var lastUpdatedFileId: String? = null
        var lastUpdatedFileSectionId: String? = null
        var lastReorderedPair: List<Pair<String, Int>>? = null
        var lastCreatedVersionLabel: String? = null
        var lastPublishedVersion: Pair<String, String>? = null
        var isFileArchived: Boolean = initialFileArchived
        var lastFilePublished: Boolean? = null

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
            return StudyFileMetadata(customId ?: "f1", sectionId, title = title, order = order, isPublished = isPublished)
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
            if (isPublished && isFileArchived) {
                throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                    message = "Cannot publish archived study file '$fileId'; unarchive it first."
                )
            }
            lastFilePublished = isPublished
        }

        override suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean, actorUid: String) {
            writeCallCount++
            lastActorUid = actorUid
            isFileArchived = isArchived
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
                isPublished = publishAsActive
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
            actorUid: String
        ) {
            writeCallCount++
            lastActorUid = actorUid
            lastPublishedVersion = fileId to versionId
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
