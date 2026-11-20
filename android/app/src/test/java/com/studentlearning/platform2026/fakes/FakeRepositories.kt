package com.studentlearning.platform2026.fakes

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.FileReadingProgress
import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.FirebaseReadiness
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfSearchResult
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.model.UserPreferences
import com.studentlearning.platform2026.domain.pdf.ArabicTextNormalizer
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.studentlearning.platform2026.domain.repository.AdminCatalogRepository
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import com.studentlearning.platform2026.domain.repository.OfflineDownloadsRepository
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfDocumentRepository
import com.studentlearning.platform2026.domain.repository.PreferencesRepository
import com.studentlearning.platform2026.domain.repository.ReadingProgressRepository
import com.studentlearning.platform2026.domain.repository.SystemStatusRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakePreferencesRepository(
    initialPreferences: UserPreferences = UserPreferences()
) : PreferencesRepository {

    private val state = MutableStateFlow(initialPreferences)
    override val preferencesFlow: Flow<UserPreferences> = state

    override suspend fun setDarkModeEnabled(enabled: Boolean) {
        state.update { it.copy(darkModeEnabled = enabled) }
    }

    override suspend fun markInitialLaunchCompleted() {
        state.update { it.copy(hasCompletedInitialLaunch = true) }
    }
}

class FakeSystemStatusRepository(
    private val readiness: FirebaseReadiness = FirebaseReadiness(
        status = FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE
    )
) : SystemStatusRepository {
    override fun getFirebaseReadiness(): FirebaseReadiness = readiness
}

class FakeCatalogRepository(
    var branchesResult: AppResult<List<StudyBranch>> = AppResult.Success(emptyList()),
    var subjectsResult: (String) -> AppResult<List<StudySubject>> = { AppResult.Success(emptyList()) },
    var sectionsResult: (String) -> AppResult<List<StudySection>> = {
        AppResult.Success(emptyList())
    },
    var studyFilesResult: (String) -> AppResult<List<StudyFileMetadata>> = {
        AppResult.Success(emptyList())
    }
) : CatalogRepository {

    override suspend fun getPublishedBranches(): AppResult<List<StudyBranch>> = branchesResult

    override suspend fun getPublishedSubjects(branchId: String): AppResult<List<StudySubject>> =
        subjectsResult(branchId)

    override suspend fun getPublishedSections(
        subjectId: String
    ): AppResult<List<StudySection>> = sectionsResult(subjectId)

    override suspend fun getPublishedStudyFiles(
        sectionId: String
    ): AppResult<List<StudyFileMetadata>> = studyFilesResult(sectionId)
}

class FakeAuthRepository(
    initialSession: AdminSession = AdminSession()
) : AuthRepository {

    private val state = MutableStateFlow(initialSession)
    override val sessionFlow: StateFlow<AdminSession> = state.asStateFlow()

    var signInHandler: suspend (String, String) -> AppResult<AdminSession> = { _, _ ->
        AppResult.Success(state.value)
    }

    var refreshHandler: suspend (Boolean) -> AppResult<AdminSession> = {
        AppResult.Success(state.value)
    }

    var signUpHandler: suspend (String, String, String) -> AppResult<AdminSession> = { email, _, _ ->
        AppResult.Success(
            AdminSession(
                isSignedIn = true,
                uid = "student_${email.hashCode()}",
                email = email,
                role = com.studentlearning.platform2026.domain.model.AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
    }

    var passwordResetHandler: suspend (String) -> AppResult<Unit> = {
        AppResult.Success(Unit)
    }

    var signOutCalls: Int = 0
    var lastResetEmailRequested: String? = null

    fun setSession(session: AdminSession) {
        state.value = session
    }

    override suspend fun refreshAdminSession(forceTokenRefresh: Boolean): AppResult<AdminSession> {
        val result = refreshHandler(forceTokenRefresh)
        if (result is AppResult.Success) {
            state.value = result.data
        }
        return result
    }

    override suspend fun signInWithEmailAndPassword(
        email: String,
        password: String
    ): AppResult<AdminSession> {
        val result = signInHandler(email, password)
        if (result is AppResult.Success) {
            state.value = result.data
        }
        return result
    }

    override suspend fun createStudentAccount(
        email: String,
        password: String,
        confirmPassword: String
    ): AppResult<AdminSession> {
        val result = signUpHandler(email, password, confirmPassword)
        if (result is AppResult.Success) {
            state.value = result.data
        }
        return result
    }

    override suspend fun sendPasswordResetEmail(email: String): AppResult<Unit> {
        lastResetEmailRequested = email
        return passwordResetHandler(email)
    }

    override suspend fun signOut(): AppResult<Unit> {
        signOutCalls++
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
        val current = state.value
        if (!current.isSignedIn || current.uid.isNullOrBlank()) {
            throw AdminException(AdminFailureReason.UNAUTHENTICATED)
        }
        if (!current.hasLoadedTokenClaims || !current.areClaimsConfirmedCurrent) {
            throw AdminException(AdminFailureReason.SESSION_VERIFICATION_REQUIRED)
        }
        if (!current.role.canAccessAdminDashboard) {
            throw AdminException(AdminFailureReason.INSUFFICIENT_ROLE)
        }
        return current
    }
}

class FakeAdminCatalogRepository : AdminCatalogRepository {
    var branches = mutableListOf<StudyBranch>()
    var subjects = mutableListOf<StudySubject>()
    var sections = mutableListOf<StudySection>()
    var studyFiles = mutableListOf<StudyFileMetadata>()

    var fetchBranchesCalls = 0
    var writeAttemptCount = 0
    var lastPublishToggle: Pair<String, Boolean>? = null
    var lastArchiveToggle: Pair<String, Boolean>? = null
    var reorderFailure: AdminException? = null

    override suspend fun getAllBranches(): AppResult<List<StudyBranch>> {
        fetchBranchesCalls++
        return AppResult.Success(branches.sortedBy { it.order })
    }

    override suspend fun createBranch(
        customId: String?,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudyBranch> {
        writeAttemptCount++
        val created = StudyBranch(
            id = customId ?: "branch_${branches.size + 1}",
            name = name,
            order = order,
            isPublished = isPublished
        )
        branches.add(created)
        return AppResult.Success(created)
    }

    override suspend fun updateBranch(branchId: String, name: String, order: Int): AppResult<Unit> {
        writeAttemptCount++
        val index = branches.indexOfFirst { it.id == branchId }
        if (index >= 0) {
            branches[index] = branches[index].copy(name = name, order = order)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun setBranchPublished(branchId: String, isPublished: Boolean): AppResult<Unit> {
        writeAttemptCount++
        lastPublishToggle = branchId to isPublished
        val index = branches.indexOfFirst { it.id == branchId }
        if (index >= 0) {
            branches[index] = branches[index].copy(isPublished = isPublished)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun getAllSubjects(branchId: String): AppResult<List<StudySubject>> {
        return AppResult.Success(subjects.filter { it.branchId == branchId }.sortedBy { it.order })
    }

    override suspend fun createSubject(
        customId: String?,
        branchId: String,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudySubject> {
        writeAttemptCount++
        val created = StudySubject(
            id = customId ?: "subject_${subjects.size + 1}",
            branchId = branchId,
            name = name,
            order = order,
            isPublished = isPublished
        )
        subjects.add(created)
        return AppResult.Success(created)
    }

    override suspend fun updateSubject(subjectId: String, name: String, order: Int): AppResult<Unit> {
        writeAttemptCount++
        val index = subjects.indexOfFirst { it.id == subjectId }
        if (index >= 0) {
            subjects[index] = subjects[index].copy(name = name, order = order)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun setSubjectPublished(subjectId: String, isPublished: Boolean): AppResult<Unit> {
        writeAttemptCount++
        lastPublishToggle = subjectId to isPublished
        val index = subjects.indexOfFirst { it.id == subjectId }
        if (index >= 0) {
            subjects[index] = subjects[index].copy(isPublished = isPublished)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun getAllSections(subjectId: String): AppResult<List<StudySection>> {
        return AppResult.Success(sections.filter { it.subjectId == subjectId }.sortedBy { it.order })
    }

    override suspend fun createSection(
        customId: String?,
        subjectId: String,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudySection> {
        writeAttemptCount++
        val created = StudySection(
            id = customId ?: "section_${sections.size + 1}",
            subjectId = subjectId,
            name = name,
            order = order,
            isPublished = isPublished
        )
        sections.add(created)
        return AppResult.Success(created)
    }

    override suspend fun updateSection(sectionId: String, name: String, order: Int): AppResult<Unit> {
        writeAttemptCount++
        val index = sections.indexOfFirst { it.id == sectionId }
        if (index >= 0) {
            sections[index] = sections[index].copy(name = name, order = order)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun setSectionPublished(sectionId: String, isPublished: Boolean): AppResult<Unit> {
        writeAttemptCount++
        lastPublishToggle = sectionId to isPublished
        val index = sections.indexOfFirst { it.id == sectionId }
        if (index >= 0) {
            sections[index] = sections[index].copy(isPublished = isPublished)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun getStudyFilesForSection(sectionId: String): AppResult<List<StudyFileMetadata>> {
        return AppResult.Success(studyFiles.filter { it.sectionId == sectionId }.sortedBy { it.order })
    }

    override suspend fun createStudyFileMetadata(
        customId: String?,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudyFileMetadata> {
        writeAttemptCount++
        val created = StudyFileMetadata(
            id = customId ?: "file_${studyFiles.size + 1}",
            sectionId = sectionId,
            title = title,
            description = description,
            order = order,
            isPublished = isPublished
        )
        studyFiles.add(created)
        return AppResult.Success(created)
    }

    override suspend fun updateStudyFileMetadata(
        fileId: String,
        sectionId: String,
        title: String,
        description: String?,
        order: Int
    ): AppResult<Unit> {
        writeAttemptCount++
        val index = studyFiles.indexOfFirst { it.id == fileId && it.sectionId == sectionId }
        if (index >= 0) {
            studyFiles[index] = studyFiles[index].copy(
                title = title,
                description = description,
                order = order
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun setStudyFilePublished(fileId: String, isPublished: Boolean): AppResult<Unit> {
        writeAttemptCount++
        lastPublishToggle = fileId to isPublished
        val index = studyFiles.indexOfFirst { it.id == fileId }
        if (index >= 0) {
            studyFiles[index] = studyFiles[index].copy(isPublished = isPublished)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean): AppResult<Unit> {
        writeAttemptCount++
        lastArchiveToggle = fileId to isArchived
        val index = studyFiles.indexOfFirst { it.id == fileId }
        if (index >= 0) {
            studyFiles[index] = studyFiles[index].copy(isArchived = isArchived)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun reorderBranches(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> {
        writeAttemptCount++
        reorderFailure?.let { return AppResult.Error(it.message ?: "Reorder failed", it) }
        val firstIdx = branches.indexOfFirst { it.id == firstId }
        val secondIdx = branches.indexOfFirst { it.id == secondId }
        if (firstIdx >= 0 && secondIdx >= 0) {
            branches[firstIdx] = branches[firstIdx].copy(order = firstNewOrder)
            branches[secondIdx] = branches[secondIdx].copy(order = secondNewOrder)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun reorderSubjects(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> {
        writeAttemptCount++
        reorderFailure?.let { return AppResult.Error(it.message ?: "Reorder failed", it) }
        val firstIdx = subjects.indexOfFirst { it.id == firstId }
        val secondIdx = subjects.indexOfFirst { it.id == secondId }
        if (firstIdx >= 0 && secondIdx >= 0) {
            subjects[firstIdx] = subjects[firstIdx].copy(order = firstNewOrder)
            subjects[secondIdx] = subjects[secondIdx].copy(order = secondNewOrder)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun reorderSections(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> {
        writeAttemptCount++
        reorderFailure?.let { return AppResult.Error(it.message ?: "Reorder failed", it) }
        val firstIdx = sections.indexOfFirst { it.id == firstId }
        val secondIdx = sections.indexOfFirst { it.id == secondId }
        if (firstIdx >= 0 && secondIdx >= 0) {
            sections[firstIdx] = sections[firstIdx].copy(order = firstNewOrder)
            sections[secondIdx] = sections[secondIdx].copy(order = secondNewOrder)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun reorderStudyFiles(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> {
        writeAttemptCount++
        reorderFailure?.let { return AppResult.Error(it.message ?: "Reorder failed", it) }
        val firstIdx = studyFiles.indexOfFirst { it.id == firstId }
        val secondIdx = studyFiles.indexOfFirst { it.id == secondId }
        if (firstIdx >= 0 && secondIdx >= 0) {
            studyFiles[firstIdx] = studyFiles[firstIdx].copy(order = firstNewOrder)
            studyFiles[secondIdx] = studyFiles[secondIdx].copy(order = secondNewOrder)
        }
        return AppResult.Success(Unit)
    }
}

class FakeReadingProgressRepository(
    initialProgress: Map<String, FileReadingProgress> = emptyMap(),
    private val clockEpochMillis: () -> Long = { System.currentTimeMillis() },
    var saveDelayMs: Long = 0L
) : ReadingProgressRepository {

    private val progressMap = MutableStateFlow(initialProgress)
    var saveCalls: Int = 0
        private set
    val recordedSaves = mutableListOf<Pair<String, Int>>()

    override fun observeProgress(fileId: String): Flow<FileReadingProgress?> =
        progressMap.map { it[fileId] }

    override suspend fun getProgress(fileId: String): FileReadingProgress? =
        progressMap.value[fileId]

    override suspend fun saveLastPage(fileId: String, pageNumber: Int, totalPages: Int) {
        if (fileId.isBlank() || totalPages <= 0) return
        if (saveDelayMs > 0L) {
            delay(saveDelayMs)
        }
        saveCalls++
        val safePage = pageNumber.coerceIn(1, totalPages)
        synchronized(recordedSaves) {
            recordedSaves.add(fileId to safePage)
        }
        progressMap.update { current ->
            current + (
                fileId to FileReadingProgress(
                    fileId = fileId,
                    lastPageNumber = safePage,
                    totalPages = totalPages,
                    updatedAtEpochMillis = clockEpochMillis()
                )
            )
        }
    }

    override suspend fun clearProgress(fileId: String) {
        progressMap.update { current -> current - fileId }
    }
}

class FakePdfDocumentSession(
    override val fileId: String,
    override val pageCount: Int,
    private val pageTexts: Map<Int, String> = emptyMap(),
    private val searchDelayMs: Long = 0L,
    private val searchPageStepDelayMs: Long = 0L
) : PdfDocumentSession {

    var closeCount: Int = 0
        private set
    val isClosed: Boolean
        get() = closeCount > 0
    var searchPagesVisited: Int = 0
        private set
    var searchCancelledCount: Int = 0
        private set

    override suspend fun getPageAspectRatio(pageIndexZeroBased: Int): Float = 0.707f

    override suspend fun searchText(query: String): PdfSearchResult {
        return try {
            if (searchDelayMs > 0L) {
                delay(searchDelayMs)
            }
            val coroutineCtx = currentCoroutineContext()
            if (searchPageStepDelayMs > 0L) {
                for (page in 1..pageCount) {
                    coroutineCtx.ensureActive()
                    searchPagesVisited++
                    delay(searchPageStepDelayMs)
                }
            }
            ArabicTextNormalizer.searchAcrossPages(
                query = query,
                pageCount = pageCount,
                extractPageText = { pageOneBased ->
                    if (searchPageStepDelayMs <= 0L) {
                        searchPagesVisited++
                    }
                    pageTexts[pageOneBased].orEmpty()
                },
                onCheckCancelled = { coroutineCtx.ensureActive() }
            )
        } catch (cancelled: CancellationException) {
            searchCancelledCount++
            throw cancelled
        }
    }

    override fun close() {
        closeCount++
    }
}

class FakePdfDocumentRepository(
    var brokerStatus: PdfBrokerConfigurationStatus =
        PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION,
    var openHandler: suspend (fileId: String, activeVersionId: String?) -> AppResult<PdfDocumentSession> =
        { fileId, _ ->
            AppResult.Success(FakePdfDocumentSession(fileId = fileId, pageCount = 10))
        }
) : PdfDocumentRepository {

    var openCalls: Int = 0
        private set

    override fun getBrokerConfigurationStatus(): PdfBrokerConfigurationStatus = brokerStatus

    override suspend fun openDocument(
        fileId: String,
        activeVersionId: String?
    ): AppResult<PdfDocumentSession> {
        openCalls++
        return openHandler(fileId, activeVersionId)
    }
}

class FakeOfflineDownloadsRepository(
    private val authRepository: FakeAuthRepository? = null
) : OfflineDownloadsRepository {

    private val savedVersionsFlow = MutableStateFlow<List<LocalDownloadedFileVersion>>(emptyList())
    private val activeTasksFlow = MutableStateFlow<Map<Pair<String, String>, DownloadTaskState>>(emptyMap())

    var startDownloadCalls: Int = 0
        private set
    var cancelDownloadCalls: Int = 0
        private set
    var retryDownloadCalls: Int = 0
        private set
    var nextStartFailureReason: DownloadFailureReason? = null

    fun putSavedVersion(version: LocalDownloadedFileVersion) {
        savedVersionsFlow.update { current ->
            current.filterNot { it.fileId == version.fileId && it.versionId == version.versionId } + version
        }
    }

    fun setTaskState(state: DownloadTaskState) {
        activeTasksFlow.update { it + ((state.fileId to state.versionId) to state) }
    }

    override fun observeAllSavedVersions(): Flow<List<LocalDownloadedFileVersion>> = savedVersionsFlow

    override fun observeFileOfflineState(
        fileId: String,
        activeVersionId: String?
    ): Flow<StudyFileOfflineState> {
        return combine(savedVersionsFlow, activeTasksFlow) { saved, tasks ->
            val forFile = saved.filter { it.fileId == fileId }
            val task = activeVersionId?.let { tasks[fileId to it] }
            StudyFileOfflineState(
                fileId = fileId,
                activeVersionId = activeVersionId,
                activeVersionDownload = task,
                savedVersions = forFile
            )
        }
    }

    override fun observeSectionOfflineStates(
        files: List<StudyFileMetadata>
    ): Flow<Map<String, StudyFileOfflineState>> {
        return combine(savedVersionsFlow, activeTasksFlow) { saved, tasks ->
            files.associate { file ->
                val forFile = saved.filter { it.fileId == file.id }
                val task = file.activeVersionId?.let { tasks[file.id to it] }
                file.id to StudyFileOfflineState(
                    fileId = file.id,
                    activeVersionId = file.activeVersionId,
                    activeVersionDownload = task,
                    savedVersions = forFile
                )
            }
        }
    }

    override suspend fun getVerifiedLocalVersion(
        fileId: String,
        versionId: String?
    ): LocalDownloadedFileVersion? {
        return if (!versionId.isNullOrBlank()) {
            savedVersionsFlow.value.firstOrNull { it.fileId == fileId && it.versionId == versionId }
        } else {
            savedVersionsFlow.value.firstOrNull { it.fileId == fileId }
        }
    }

    override suspend fun getSavedVersionsForFile(fileId: String): List<LocalDownloadedFileVersion> {
        return savedVersionsFlow.value.filter { it.fileId == fileId }
    }

    override suspend fun getSavedVersionsForSection(sectionId: String): List<LocalDownloadedFileVersion> {
        return savedVersionsFlow.value.filter { it.sectionId == sectionId }
    }

    override suspend fun startDownload(
        file: StudyFileMetadata,
        expectedFileSizeBytes: Long?,
        expectedChecksumSha256: String?
    ): AppResult<DownloadTaskState> {
        startDownloadCalls++
        return startDownload(
            OfflineDownloadRequest(
                fileId = file.id,
                versionId = file.activeVersionId.orEmpty(),
                versionNumber = file.activeVersionNumber,
                versionLabel = file.activeVersionLabel,
                fileTitle = file.title,
                sectionId = file.sectionId,
                expectedFileSizeBytes = expectedFileSizeBytes,
                expectedChecksumSha256 = expectedChecksumSha256
            )
        )
    }

    override suspend fun startDownload(request: OfflineDownloadRequest): AppResult<DownloadTaskState> {
        val session = authRepository?.sessionFlow?.value
        if (session == null || !session.isSignedIn || !session.canDownloadOfflineFiles) {
            val message = "Signing in with a student account is required."
            return AppResult.Error(
                message = message,
                cause = DownloadException(DownloadFailureReason.UNAUTHENTICATED_GUEST, message)
            )
        }
        nextStartFailureReason?.let { reason ->
            return AppResult.Error(
                message = "Failed: $reason",
                cause = DownloadException(reason, "Failed: $reason")
            )
        }
        val queued = DownloadTaskState(
            fileId = request.fileId,
            versionId = request.versionId,
            versionNumber = request.versionNumber,
            versionLabel = request.versionLabel,
            status = DownloadStatus.QUEUED
        )
        setTaskState(queued)
        return AppResult.Success(queued)
    }

    override suspend fun retryDownload(
        file: StudyFileMetadata,
        expectedFileSizeBytes: Long?,
        expectedChecksumSha256: String?
    ): AppResult<DownloadTaskState> {
        retryDownloadCalls++
        return startDownload(file, expectedFileSizeBytes, expectedChecksumSha256)
    }

    override suspend fun cancelDownload(fileId: String, versionId: String): AppResult<Unit> {
        cancelDownloadCalls++
        setTaskState(
            DownloadTaskState(
                fileId = fileId,
                versionId = versionId,
                status = DownloadStatus.CANCELLED
            )
        )
        return AppResult.Success(Unit)
    }

    override suspend fun deleteLocalVersion(fileId: String, versionId: String): AppResult<Unit> {
        savedVersionsFlow.update { current ->
            current.filterNot { it.fileId == fileId && it.versionId == versionId }
        }
        activeTasksFlow.update { it - (fileId to versionId) }
        return AppResult.Success(Unit)
    }

    override suspend fun cleanupOrphanedTempFiles() = Unit
}
