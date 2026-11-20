package com.studentlearning.platform2026.presentation.admin

import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject

enum class AdminManagementSection {
    BRANCHES,
    SUBJECTS,
    SECTIONS,
    PDF_FILES
}

enum class AdminSuccessMessage {
    BRANCH_CREATED,
    BRANCH_UPDATED,
    SUBJECT_CREATED,
    SUBJECT_UPDATED,
    SECTION_CREATED,
    SECTION_UPDATED,
    FILE_CREATED,
    FILE_UPDATED,
    PUBLISH_UPDATED,
    ARCHIVE_UPDATED,
    REORDERED
}

data class AdminFeedbackState(
    val isError: Boolean,
    val successMessage: AdminSuccessMessage? = null,
    val failureReason: AdminFailureReason? = null,
    val validationError: AdminValidationError? = null
)

sealed interface AdminEditorDialogState {
    data class BranchEditor(
        val editing: StudyBranch? = null,
        val suggestedOrder: Int = 1
    ) : AdminEditorDialogState

    data class SubjectEditor(
        val branch: StudyBranch,
        val editing: StudySubject? = null,
        val suggestedOrder: Int = 1
    ) : AdminEditorDialogState

    data class SectionEditor(
        val subject: StudySubject,
        val editing: StudySection? = null,
        val suggestedOrder: Int = 1
    ) : AdminEditorDialogState

    data class StudyFileEditor(
        val section: StudySection,
        val editing: StudyFileMetadata? = null,
        val suggestedOrder: Int = 1
    ) : AdminEditorDialogState
}

sealed interface AdminConfirmationAction {
    data class UnpublishBranch(val branch: StudyBranch) : AdminConfirmationAction
    data class UnpublishSubject(val subject: StudySubject) : AdminConfirmationAction
    data class UnpublishSection(val section: StudySection) : AdminConfirmationAction
    data class UnpublishStudyFile(val file: StudyFileMetadata) : AdminConfirmationAction
    data class ArchiveStudyFile(val file: StudyFileMetadata) : AdminConfirmationAction
}

data class AdminPortalUiState(
    val isCheckingSession: Boolean = true,
    val isSigningIn: Boolean = false,
    val isSubmitting: Boolean = false,
    val session: AdminSession = AdminSession(),
    val authErrorReason: AdminFailureReason? = null,
    val authValidationError: AdminValidationError? = null,
    val activeTab: AdminManagementSection = AdminManagementSection.BRANCHES,
    val branches: List<StudyBranch> = emptyList(),
    val selectedBranchId: String? = null,
    val isLoadingBranches: Boolean = false,
    val branchesError: AdminFailureReason? = null,
    val subjects: List<StudySubject> = emptyList(),
    val selectedSubjectId: String? = null,
    val isLoadingSubjects: Boolean = false,
    val subjectsError: AdminFailureReason? = null,
    val sections: List<StudySection> = emptyList(),
    val selectedSectionId: String? = null,
    val isLoadingSections: Boolean = false,
    val sectionsError: AdminFailureReason? = null,
    val studyFiles: List<StudyFileMetadata> = emptyList(),
    val isLoadingStudyFiles: Boolean = false,
    val studyFilesError: AdminFailureReason? = null,
    val editorDialog: AdminEditorDialogState? = null,
    val confirmationAction: AdminConfirmationAction? = null,
    val feedback: AdminFeedbackState? = null
) {
    val selectedBranch: StudyBranch?
        get() = branches.firstOrNull { it.id == selectedBranchId }

    val selectedSubject: StudySubject?
        get() = subjects.firstOrNull { it.id == selectedSubjectId }

    val selectedSection: StudySection?
        get() = sections.firstOrNull { it.id == selectedSectionId }
}
