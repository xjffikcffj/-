package com.studentlearning.platform2026.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.AdminCatalogRepository
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AdminPortalViewModel(
    private val authRepository: AuthRepository,
    private val adminCatalogRepository: AdminCatalogRepository,
    private val r2BrokerStatus: PdfBrokerConfigurationStatus = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AdminPortalUiState(r2BrokerStatus = r2BrokerStatus)
    )
    val uiState: StateFlow<AdminPortalUiState> = _uiState.asStateFlow()

    private var lastLoadedAdminUid: String? = null

    init {
        viewModelScope.launch {
            authRepository.sessionFlow.collect { session ->
                val previousSession = _uiState.value.session
                val uidChanged = previousSession.uid != null && previousSession.uid != session.uid
                _uiState.update { it.copy(session = session) }

                if (!session.isSignedIn || !session.hasRecognizedAdminRole || uidChanged) {
                    lastLoadedAdminUid = null
                    clearCatalogState()
                }
            }
        }
        refreshSession(forceTokenRefresh = true)
    }

    fun refreshSession(forceTokenRefresh: Boolean = true) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingSession = true,
                    authErrorReason = null,
                    authValidationError = null
                )
            }
            when (val result = authRepository.refreshAdminSession(forceTokenRefresh)) {
                is AppResult.Success -> {
                    val session = result.data
                    _uiState.update {
                        it.copy(
                            isCheckingSession = false,
                            session = session,
                            authErrorReason = null,
                            authValidationError = null
                        )
                    }
                    if (session.canPerformAdminActions) {
                        lastLoadedAdminUid = session.uid
                        loadBranches()
                    } else {
                        lastLoadedAdminUid = null
                        clearCatalogState()
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    val preservedSession = authRepository.sessionFlow.value
                    _uiState.update {
                        it.copy(
                            isCheckingSession = false,
                            session = preservedSession,
                            authErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN,
                            authValidationError = ex?.validationError
                        )
                    }
                    if (!preservedSession.isSignedIn || !preservedSession.hasRecognizedAdminRole) {
                        lastLoadedAdminUid = null
                        clearCatalogState()
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (_uiState.value.isSigningIn) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSigningIn = true,
                    authErrorReason = null,
                    authValidationError = null
                )
            }
            when (val result = authRepository.signInWithEmailAndPassword(email, password)) {
                is AppResult.Success -> {
                    val session = result.data
                    _uiState.update {
                        it.copy(
                            isSigningIn = false,
                            session = session,
                            authErrorReason = null,
                            authValidationError = null
                        )
                    }
                    if (session.canPerformAdminActions) {
                        lastLoadedAdminUid = session.uid
                        loadBranches()
                    } else {
                        lastLoadedAdminUid = null
                        clearCatalogState()
                    }
                }
                is AppResult.Error -> {
                    val ex = result.cause as? AdminException
                    _uiState.update {
                        it.copy(
                            isSigningIn = false,
                            session = authRepository.sessionFlow.value,
                            authErrorReason = ex?.reason ?: AdminFailureReason.UNKNOWN,
                            authValidationError = ex?.validationError
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            lastLoadedAdminUid = null
            authRepository.signOut()
            _uiState.value = AdminPortalUiState(
                isCheckingSession = false,
                session = AdminSession(),
                r2BrokerStatus = r2BrokerStatus
            )
        }
    }

    fun selectTab(tab: AdminManagementSection) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    fun openBranchSubjects(branch: StudyBranch) {
        _uiState.update {
            it.copy(
                selectedBranchId = branch.id,
                selectedSubjectId = null,
                selectedSectionId = null,
                subjects = emptyList(),
                sections = emptyList(),
                studyFiles = emptyList(),
                activeTab = AdminManagementSection.SUBJECTS
            )
        }
        loadSubjects(branch.id)
    }

    fun openSubjectSections(subject: StudySubject) {
        _uiState.update {
            it.copy(
                selectedSubjectId = subject.id,
                selectedSectionId = null,
                sections = emptyList(),
                studyFiles = emptyList(),
                activeTab = AdminManagementSection.SECTIONS
            )
        }
        loadSections(subject.id)
    }

    fun openSectionFiles(section: StudySection) {
        _uiState.update {
            it.copy(
                selectedSectionId = section.id,
                studyFiles = emptyList(),
                activeTab = AdminManagementSection.PDF_FILES
            )
        }
        loadStudyFiles(section.id)
    }

    fun loadBranches() {
        if (!_uiState.value.session.canPerformAdminActions) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBranches = true, branchesError = null) }
            when (val result = adminCatalogRepository.getAllBranches()) {
                is AppResult.Success -> {
                    val list = result.data
                    val currentSelected = _uiState.value.selectedBranchId
                        ?.takeIf { id -> list.any { it.id == id } }
                    _uiState.update {
                        it.copy(
                            isLoadingBranches = false,
                            branches = list,
                            selectedBranchId = currentSelected
                        )
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? AdminException)?.reason ?: AdminFailureReason.UNKNOWN
                    _uiState.update { it.copy(isLoadingBranches = false, branchesError = reason) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun loadSubjects(branchId: String = _uiState.value.selectedBranchId.orEmpty()) {
        if (!_uiState.value.session.canPerformAdminActions || branchId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSubjects = true, subjectsError = null) }
            when (val result = adminCatalogRepository.getAllSubjects(branchId)) {
                is AppResult.Success -> {
                    val list = result.data
                    val currentSelected = _uiState.value.selectedSubjectId
                        ?.takeIf { id -> list.any { it.id == id } }
                    _uiState.update {
                        it.copy(
                            isLoadingSubjects = false,
                            subjects = list,
                            selectedSubjectId = currentSelected
                        )
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? AdminException)?.reason ?: AdminFailureReason.UNKNOWN
                    _uiState.update { it.copy(isLoadingSubjects = false, subjectsError = reason) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun loadSections(subjectId: String = _uiState.value.selectedSubjectId.orEmpty()) {
        if (!_uiState.value.session.canPerformAdminActions || subjectId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSections = true, sectionsError = null) }
            when (val result = adminCatalogRepository.getAllSections(subjectId)) {
                is AppResult.Success -> {
                    val list = result.data
                    val currentSelected = _uiState.value.selectedSectionId
                        ?.takeIf { id -> list.any { it.id == id } }
                    _uiState.update {
                        it.copy(
                            isLoadingSections = false,
                            sections = list,
                            selectedSectionId = currentSelected
                        )
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? AdminException)?.reason ?: AdminFailureReason.UNKNOWN
                    _uiState.update { it.copy(isLoadingSections = false, sectionsError = reason) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun loadStudyFiles(sectionId: String = _uiState.value.selectedSectionId.orEmpty()) {
        if (!_uiState.value.session.canPerformAdminActions || sectionId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStudyFiles = true, studyFilesError = null) }
            when (val result = adminCatalogRepository.getStudyFilesForSection(sectionId)) {
                is AppResult.Success -> {
                    val files = result.data
                    _uiState.update { state ->
                        val refreshedDialogFile = state.versionsDialogFile?.let { currentFile ->
                            files.firstOrNull { it.id == currentFile.id } ?: currentFile
                        }
                        state.copy(
                            isLoadingStudyFiles = false,
                            studyFiles = files,
                            versionsDialogFile = refreshedDialogFile
                        )
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? AdminException)?.reason ?: AdminFailureReason.UNKNOWN
                    _uiState.update { it.copy(isLoadingStudyFiles = false, studyFilesError = reason) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun openFileVersionsManager(file: StudyFileMetadata) {
        if (!_uiState.value.session.canPerformAdminActions) return
        _uiState.update {
            it.copy(
                versionsDialogFile = file,
                fileVersions = emptyList(),
                isLoadingFileVersions = true,
                fileVersionsError = null
            )
        }
        loadFileVersions(file.id)
    }

    fun dismissFileVersionsManager() {
        _uiState.update {
            it.copy(
                versionsDialogFile = null,
                fileVersions = emptyList(),
                isLoadingFileVersions = false,
                fileVersionsError = null
            )
        }
    }

    fun loadFileVersions(fileId: String = _uiState.value.versionsDialogFile?.id.orEmpty()) {
        if (!_uiState.value.session.canPerformAdminActions || fileId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFileVersions = true, fileVersionsError = null) }
            when (val result = adminCatalogRepository.getStudyFileVersions(fileId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingFileVersions = false,
                            fileVersions = result.data
                        )
                    }
                }
                is AppResult.Error -> {
                    val reason = (result.cause as? AdminException)?.reason ?: AdminFailureReason.UNKNOWN
                    _uiState.update {
                        it.copy(
                            isLoadingFileVersions = false,
                            fileVersionsError = reason
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun openCreateBranchDialog() {
        if (!ensureCanPerformOwnerOnlyWrite()) return
        val nextOrder = (_uiState.value.branches.maxOfOrNull { it.order } ?: 0) + 1
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.BranchEditor(suggestedOrder = nextOrder)) }
    }

    fun openEditBranchDialog(branch: StudyBranch) {
        if (!ensureCanPerformOwnerOnlyWrite()) return
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.BranchEditor(editing = branch, suggestedOrder = branch.order)) }
    }

    fun openCreateSubjectDialog() {
        if (!ensureCanPerformAdminWrite()) return
        val branch = _uiState.value.selectedBranch ?: return
        val nextOrder = (_uiState.value.subjects.maxOfOrNull { it.order } ?: 0) + 1
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.SubjectEditor(branch = branch, suggestedOrder = nextOrder)) }
    }

    fun openEditSubjectDialog(subject: StudySubject) {
        if (!ensureCanPerformAdminWrite()) return
        val branch = _uiState.value.selectedBranch ?: return
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.SubjectEditor(branch = branch, editing = subject, suggestedOrder = subject.order)) }
    }

    fun openCreateSectionDialog() {
        if (!ensureCanPerformAdminWrite()) return
        val subject = _uiState.value.selectedSubject ?: return
        val nextOrder = (_uiState.value.sections.maxOfOrNull { it.order } ?: 0) + 1
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.SectionEditor(subject = subject, suggestedOrder = nextOrder)) }
    }

    fun openEditSectionDialog(section: StudySection) {
        if (!ensureCanPerformAdminWrite()) return
        val subject = _uiState.value.selectedSubject ?: return
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.SectionEditor(subject = subject, editing = section, suggestedOrder = section.order)) }
    }

    fun openCreateStudyFileDialog() {
        if (!ensureCanPerformAdminWrite()) return
        val section = _uiState.value.selectedSection ?: return
        val nextOrder = (_uiState.value.studyFiles.maxOfOrNull { it.order } ?: 0) + 1
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.StudyFileEditor(section = section, suggestedOrder = nextOrder)) }
    }

    fun openEditStudyFileDialog(file: StudyFileMetadata) {
        if (!ensureCanPerformAdminWrite()) return
        val section = _uiState.value.selectedSection ?: return
        _uiState.update { it.copy(editorDialog = AdminEditorDialogState.StudyFileEditor(section = section, editing = file, suggestedOrder = file.order)) }
    }

    fun openCreateVersionDialog() {
        if (!ensureCanPerformAdminWrite()) return
        val file = _uiState.value.versionsDialogFile ?: return
        val nextVersionNumber = (_uiState.value.fileVersions.maxOfOrNull { it.versionNumber } ?: 0) + 1
        _uiState.update {
            it.copy(
                editorDialog = AdminEditorDialogState.StudyFileVersionEditor(
                    file = file,
                    suggestedVersionNumber = nextVersionNumber
                )
            )
        }
    }

    fun openEditVersionDialog(version: StudyFileVersionMetadata) {
        if (!ensureCanPerformAdminWrite()) return
        val file = _uiState.value.versionsDialogFile ?: return
        _uiState.update {
            it.copy(
                editorDialog = AdminEditorDialogState.StudyFileVersionEditor(
                    file = file,
                    editing = version,
                    suggestedVersionNumber = version.versionNumber
                )
            )
        }
    }

    fun dismissEditorDialog() {
        _uiState.update { it.copy(editorDialog = null) }
    }

    fun saveBranch(editing: StudyBranch?, customId: String?, name: String, order: Int, isPublished: Boolean) {
        if (!ensureCanPerformOwnerOnlyWrite()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = if (editing == null) {
                adminCatalogRepository.createBranch(customId, name, order, isPublished)
            } else {
                adminCatalogRepository.updateBranch(editing.id, name, order)
            }
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            editorDialog = null,
                            feedback = AdminFeedbackState(
                                isError = false,
                                successMessage = if (editing == null) AdminSuccessMessage.BRANCH_CREATED else AdminSuccessMessage.BRANCH_UPDATED
                            )
                        )
                    }
                    loadBranches()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    fun saveSubject(branchId: String, editing: StudySubject?, customId: String?, name: String, order: Int, isPublished: Boolean) {
        if (!ensureCanPerformAdminWrite()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = if (editing == null) {
                adminCatalogRepository.createSubject(customId, branchId, name, order, isPublished)
            } else {
                adminCatalogRepository.updateSubject(editing.id, name, order)
            }
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            editorDialog = null,
                            feedback = AdminFeedbackState(
                                isError = false,
                                successMessage = if (editing == null) AdminSuccessMessage.SUBJECT_CREATED else AdminSuccessMessage.SUBJECT_UPDATED
                            )
                        )
                    }
                    loadSubjects(branchId)
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    fun saveSection(subjectId: String, editing: StudySection?, customId: String?, name: String, order: Int, isPublished: Boolean) {
        if (!ensureCanPerformAdminWrite()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = if (editing == null) {
                adminCatalogRepository.createSection(customId, subjectId, name, order, isPublished)
            } else {
                adminCatalogRepository.updateSection(editing.id, name, order)
            }
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            editorDialog = null,
                            feedback = AdminFeedbackState(
                                isError = false,
                                successMessage = if (editing == null) AdminSuccessMessage.SECTION_CREATED else AdminSuccessMessage.SECTION_UPDATED
                            )
                        )
                    }
                    loadSections(subjectId)
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    fun saveStudyFile(
        sectionId: String,
        editing: StudyFileMetadata?,
        customId: String?,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean
    ) {
        if (!ensureCanPerformAdminWrite()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = if (editing == null) {
                adminCatalogRepository.createStudyFileMetadata(
                    customId = customId,
                    sectionId = sectionId,
                    title = title,
                    description = description,
                    order = order,
                    isPublished = isPublished
                )
            } else {
                adminCatalogRepository.updateStudyFileMetadata(
                    fileId = editing.id,
                    sectionId = editing.sectionId.ifBlank { sectionId },
                    title = title,
                    description = description,
                    order = order
                )
            }
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            editorDialog = null,
                            feedback = AdminFeedbackState(
                                isError = false,
                                successMessage = if (editing == null) AdminSuccessMessage.FILE_CREATED else AdminSuccessMessage.FILE_UPDATED
                            )
                        )
                    }
                    loadStudyFiles(sectionId)
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    fun saveStudyFileVersion(
        fileId: String,
        editing: StudyFileVersionMetadata?,
        customVersionId: String?,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishAsActive: Boolean
    ) {
        if (!ensureCanPerformAdminWrite()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = if (editing == null) {
                adminCatalogRepository.createStudyFileVersion(
                    fileId = fileId,
                    versionNumber = versionNumber,
                    versionLabel = versionLabel,
                    versionNotes = versionNotes,
                    publishAsActive = publishAsActive,
                    customVersionId = customVersionId
                )
            } else {
                adminCatalogRepository.updateStudyFileVersionMetadata(
                    fileId = fileId,
                    versionId = editing.id,
                    versionLabel = versionLabel,
                    versionNotes = versionNotes
                )
            }
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            editorDialog = null,
                            feedback = AdminFeedbackState(
                                isError = false,
                                successMessage = if (editing == null) {
                                    AdminSuccessMessage.VERSION_CREATED
                                } else {
                                    AdminSuccessMessage.VERSION_UPDATED
                                }
                            )
                        )
                    }
                    loadFileVersions(fileId)
                    loadStudyFiles()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    fun requestPublishStudyFileVersion(version: StudyFileVersionMetadata) {
        if (!ensureCanPerformAdminWrite()) return
        val file = _uiState.value.versionsDialogFile ?: return
        _uiState.update {
            it.copy(
                confirmationAction = AdminConfirmationAction.PublishStudyFileVersion(
                    file = file,
                    version = version
                )
            )
        }
    }

    fun requestToggleBranchPublished(branch: StudyBranch) {
        if (!ensureCanPerformOwnerOnlyWrite()) return
        if (branch.isPublished) {
            _uiState.update { it.copy(confirmationAction = AdminConfirmationAction.UnpublishBranch(branch)) }
        } else {
            executeSetBranchPublished(branch.id, isPublished = true)
        }
    }

    fun requestToggleSubjectPublished(subject: StudySubject) {
        if (subject.isPublished) {
            if (!ensureCanPerformOwnerOnlyWrite()) return
            _uiState.update { it.copy(confirmationAction = AdminConfirmationAction.UnpublishSubject(subject)) }
        } else {
            if (!ensureCanPerformAdminWrite()) return
            executeSetSubjectPublished(subject.id, isPublished = true)
        }
    }

    fun requestToggleSectionPublished(section: StudySection) {
        if (!ensureCanPerformAdminWrite()) return
        if (section.isPublished) {
            _uiState.update { it.copy(confirmationAction = AdminConfirmationAction.UnpublishSection(section)) }
        } else {
            executeSetSectionPublished(section.id, isPublished = true)
        }
    }

    fun requestToggleStudyFilePublished(file: StudyFileMetadata) {
        if (!ensureCanPerformAdminWrite()) return
        if (file.isPublished) {
            _uiState.update { it.copy(confirmationAction = AdminConfirmationAction.UnpublishStudyFile(file)) }
        } else {
            executeSetStudyFilePublished(file.id, isPublished = true)
        }
    }

    fun requestToggleStudyFileArchived(file: StudyFileMetadata) {
        if (!ensureCanPerformAdminWrite()) return
        if (!file.isArchived) {
            _uiState.update { it.copy(confirmationAction = AdminConfirmationAction.ArchiveStudyFile(file)) }
        } else {
            executeSetStudyFileArchived(file.id, isArchived = false)
        }
    }

    fun confirmPendingAction() {
        val pending = _uiState.value.confirmationAction ?: return
        val allowed = when (pending) {
            is AdminConfirmationAction.UnpublishBranch,
            is AdminConfirmationAction.UnpublishSubject -> ensureCanPerformOwnerOnlyWrite()
            else -> ensureCanPerformAdminWrite()
        }
        if (!allowed) {
            _uiState.update { it.copy(confirmationAction = null) }
            return
        }
        _uiState.update { it.copy(confirmationAction = null) }
        when (pending) {
            is AdminConfirmationAction.UnpublishBranch ->
                executeSetBranchPublished(pending.branch.id, isPublished = false)
            is AdminConfirmationAction.UnpublishSubject ->
                executeSetSubjectPublished(pending.subject.id, isPublished = false)
            is AdminConfirmationAction.UnpublishSection ->
                executeSetSectionPublished(pending.section.id, isPublished = false)
            is AdminConfirmationAction.UnpublishStudyFile ->
                executeSetStudyFilePublished(pending.file.id, isPublished = false)
            is AdminConfirmationAction.ArchiveStudyFile ->
                executeSetStudyFileArchived(pending.file.id, isArchived = true)
            is AdminConfirmationAction.PublishStudyFileVersion ->
                executePublishStudyFileVersion(pending.file.id, pending.version.id)
        }
    }

    fun dismissConfirmationDialog() {
        _uiState.update { it.copy(confirmationAction = null) }
    }

    fun uploadAndVerifyVersionPdf(
        fileId: String,
        versionId: String,
        binarySource: PdfUploadBinarySource
    ) {
        if (!ensureCanPerformAdminWrite()) return
        if (_uiState.value.isSubmitting || _uiState.value.uploadingVersionId != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    uploadingVersionId = versionId,
                    feedback = null
                )
            }
            when (val result = adminCatalogRepository.uploadAndVerifyStudyFileVersionPdf(
                fileId = fileId,
                versionId = versionId,
                binarySource = binarySource
            )) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            uploadingVersionId = null,
                            feedback = AdminFeedbackState(
                                isError = false,
                                successMessage = AdminSuccessMessage.VERSION_PDF_VERIFIED
                            )
                        )
                    }
                    loadFileVersions(fileId)
                    loadStudyFiles()
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(uploadingVersionId = null) }
                    handleSubmissionError(result.cause)
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun reportVersionUploadError(
        validationError: AdminValidationError?,
        reason: AdminFailureReason = AdminFailureReason.VALIDATION_ERROR
    ) {
        _uiState.update {
            it.copy(
                isSubmitting = false,
                uploadingVersionId = null,
                feedback = AdminFeedbackState(
                    isError = true,
                    failureReason = reason,
                    validationError = validationError
                )
            )
        }
    }

    fun moveBranch(branch: StudyBranch, moveUp: Boolean) {
        if (!ensureCanPerformOwnerOnlyWrite()) return
        val list = _uiState.value.branches
        val swapPair = computeSwappedOrders(list.indexOfFirst { it.id == branch.id }, list.map { it.order }, moveUp) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = adminCatalogRepository.reorderBranches(
                firstId = list[swapPair.firstIndex].id,
                firstNewOrder = swapPair.firstNewOrder,
                secondId = list[swapPair.secondIndex].id,
                secondNewOrder = swapPair.secondNewOrder
            )
            finishReorder(result) { loadBranches() }
        }
    }

    fun moveSubject(subject: StudySubject, moveUp: Boolean) {
        if (!ensureCanPerformAdminWrite()) return
        val list = _uiState.value.subjects
        val swapPair = computeSwappedOrders(list.indexOfFirst { it.id == subject.id }, list.map { it.order }, moveUp) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = adminCatalogRepository.reorderSubjects(
                firstId = list[swapPair.firstIndex].id,
                firstNewOrder = swapPair.firstNewOrder,
                secondId = list[swapPair.secondIndex].id,
                secondNewOrder = swapPair.secondNewOrder
            )
            finishReorder(result) { loadSubjects() }
        }
    }

    fun moveSection(section: StudySection, moveUp: Boolean) {
        if (!ensureCanPerformAdminWrite()) return
        val list = _uiState.value.sections
        val swapPair = computeSwappedOrders(list.indexOfFirst { it.id == section.id }, list.map { it.order }, moveUp) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = adminCatalogRepository.reorderSections(
                firstId = list[swapPair.firstIndex].id,
                firstNewOrder = swapPair.firstNewOrder,
                secondId = list[swapPair.secondIndex].id,
                secondNewOrder = swapPair.secondNewOrder
            )
            finishReorder(result) { loadSections() }
        }
    }

    fun moveStudyFile(file: StudyFileMetadata, moveUp: Boolean) {
        if (!ensureCanPerformAdminWrite()) return
        val list = _uiState.value.studyFiles
        val swapPair = computeSwappedOrders(list.indexOfFirst { it.id == file.id }, list.map { it.order }, moveUp) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            val result = adminCatalogRepository.reorderStudyFiles(
                firstId = list[swapPair.firstIndex].id,
                firstNewOrder = swapPair.firstNewOrder,
                secondId = list[swapPair.secondIndex].id,
                secondNewOrder = swapPair.secondNewOrder
            )
            finishReorder(result) { loadStudyFiles() }
        }
    }

    private fun ensureCanPerformAdminWrite(): Boolean {
        val session = _uiState.value.session
        if (session.canPerformAdminActions) {
            return true
        }
        val reason = when {
            !session.isSignedIn -> AdminFailureReason.UNAUTHENTICATED
            !session.hasLoadedTokenClaims || !session.areClaimsConfirmedCurrent ->
                AdminFailureReason.SESSION_VERIFICATION_REQUIRED
            else -> AdminFailureReason.INSUFFICIENT_ROLE
        }
        _uiState.update {
            it.copy(
                isSubmitting = false,
                feedback = AdminFeedbackState(
                    isError = true,
                    failureReason = reason
                )
            )
        }
        return false
    }

    private fun ensureCanPerformOwnerOnlyWrite(): Boolean {
        if (!ensureCanPerformAdminWrite()) return false
        val session = _uiState.value.session
        if (session.canPerformOwnerOnlyActions) {
            return true
        }
        _uiState.update {
            it.copy(
                isSubmitting = false,
                feedback = AdminFeedbackState(
                    isError = true,
                    failureReason = AdminFailureReason.INSUFFICIENT_ROLE
                )
            )
        }
        return false
    }

    private fun executeSetBranchPublished(branchId: String, isPublished: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            when (val result = adminCatalogRepository.setBranchPublished(branchId, isPublished)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            feedback = AdminFeedbackState(isError = false, successMessage = AdminSuccessMessage.PUBLISH_UPDATED)
                        )
                    }
                    loadBranches()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executeSetSubjectPublished(subjectId: String, isPublished: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            when (val result = adminCatalogRepository.setSubjectPublished(subjectId, isPublished)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            feedback = AdminFeedbackState(isError = false, successMessage = AdminSuccessMessage.PUBLISH_UPDATED)
                        )
                    }
                    loadSubjects()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executeSetSectionPublished(sectionId: String, isPublished: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            when (val result = adminCatalogRepository.setSectionPublished(sectionId, isPublished)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            feedback = AdminFeedbackState(isError = false, successMessage = AdminSuccessMessage.PUBLISH_UPDATED)
                        )
                    }
                    loadSections()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executeSetStudyFilePublished(fileId: String, isPublished: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            when (val result = adminCatalogRepository.setStudyFilePublished(fileId, isPublished)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            feedback = AdminFeedbackState(isError = false, successMessage = AdminSuccessMessage.PUBLISH_UPDATED)
                        )
                    }
                    loadStudyFiles()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executeSetStudyFileArchived(fileId: String, isArchived: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            when (val result = adminCatalogRepository.setStudyFileArchived(fileId, isArchived)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            feedback = AdminFeedbackState(isError = false, successMessage = AdminSuccessMessage.ARCHIVE_UPDATED)
                        )
                    }
                    loadStudyFiles()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executePublishStudyFileVersion(fileId: String, versionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, feedback = null) }
            when (val result = adminCatalogRepository.publishStudyFileVersion(fileId, versionId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            feedback = AdminFeedbackState(
                                isError = false,
                                successMessage = AdminSuccessMessage.VERSION_PUBLISHED
                            )
                        )
                    }
                    loadFileVersions(fileId)
                    loadStudyFiles()
                }
                is AppResult.Error -> handleSubmissionError(result.cause)
                AppResult.Loading -> Unit
            }
        }
    }

    private fun finishReorder(
        result: AppResult<Unit>,
        onSuccessReload: () -> Unit
    ) {
        if (result is AppResult.Error) {
            handleSubmissionError(result.cause)
            return
        }
        _uiState.update {
            it.copy(
                isSubmitting = false,
                feedback = AdminFeedbackState(isError = false, successMessage = AdminSuccessMessage.REORDERED)
            )
        }
        onSuccessReload()
    }

    private fun handleSubmissionError(cause: Throwable?) {
        val ex = cause as? AdminException
        _uiState.update {
            it.copy(
                isSubmitting = false,
                feedback = AdminFeedbackState(
                    isError = true,
                    failureReason = ex?.reason ?: AdminFailureReason.UNKNOWN,
                    validationError = ex?.validationError
                )
            )
        }
    }

    private fun clearCatalogState() {
        _uiState.update {
            it.copy(
                branches = emptyList(),
                subjects = emptyList(),
                sections = emptyList(),
                studyFiles = emptyList(),
                selectedBranchId = null,
                selectedSubjectId = null,
                selectedSectionId = null,
                versionsDialogFile = null,
                fileVersions = emptyList(),
                isLoadingFileVersions = false,
                fileVersionsError = null,
                editorDialog = null,
                confirmationAction = null
            )
        }
    }

    private data class OrderSwap(
        val firstIndex: Int,
        val firstNewOrder: Int,
        val secondIndex: Int,
        val secondNewOrder: Int
    )

    private fun computeSwappedOrders(
        index: Int,
        orders: List<Int>,
        moveUp: Boolean
    ): OrderSwap? {
        if (index !in orders.indices) return null
        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in orders.indices) return null

        val currentOrder = orders[index].coerceAtLeast(0)
        val targetOrder = orders[targetIndex].coerceAtLeast(0)

        return if (currentOrder != targetOrder) {
            OrderSwap(
                firstIndex = index,
                firstNewOrder = targetOrder,
                secondIndex = targetIndex,
                secondNewOrder = currentOrder
            )
        } else if (moveUp) {
            val lower = targetIndex
            val higher = index
            OrderSwap(
                firstIndex = higher,
                firstNewOrder = orders[lower].coerceAtLeast(0),
                secondIndex = lower,
                secondNewOrder = orders[lower].coerceAtLeast(0) + 1
            )
        } else {
            val lower = index
            val higher = targetIndex
            OrderSwap(
                firstIndex = lower,
                firstNewOrder = orders[higher].coerceAtLeast(0) + 1,
                secondIndex = higher,
                secondNewOrder = orders[higher].coerceAtLeast(0)
            )
        }
    }

    companion object {
        fun provideFactory(
            authRepository: AuthRepository,
            adminCatalogRepository: AdminCatalogRepository,
            r2BrokerStatus: PdfBrokerConfigurationStatus = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AdminPortalViewModel(
                    authRepository = authRepository,
                    adminCatalogRepository = adminCatalogRepository,
                    r2BrokerStatus = r2BrokerStatus
                ) as T
            }
        }
    }
}
