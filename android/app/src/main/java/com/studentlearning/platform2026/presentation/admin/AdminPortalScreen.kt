package com.studentlearning.platform2026.presentation.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.presentation.account.resolveAuthErrorText
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.components.HaqibatiTopBar
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun AdminPortalScreen(
    viewModel: AdminPortalViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AdminPortalScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onRefreshSession = { viewModel.refreshSession(forceTokenRefresh = true) },
        onSignIn = viewModel::signIn,
        onSignOut = viewModel::signOut,
        onSelectTab = viewModel::selectTab,
        onDismissFeedback = viewModel::dismissFeedback,
        onRetryBranches = viewModel::loadBranches,
        onRetrySubjects = { viewModel.loadSubjects() },
        onRetrySections = { viewModel.loadSections() },
        onRetryStudyFiles = { viewModel.loadStudyFiles() },
        onOpenCreateBranch = viewModel::openCreateBranchDialog,
        onOpenEditBranch = viewModel::openEditBranchDialog,
        onOpenBranchSubjects = viewModel::openBranchSubjects,
        onToggleBranchPublish = viewModel::requestToggleBranchPublished,
        onMoveBranch = viewModel::moveBranch,
        onOpenCreateSubject = viewModel::openCreateSubjectDialog,
        onOpenEditSubject = viewModel::openEditSubjectDialog,
        onOpenSubjectSections = viewModel::openSubjectSections,
        onToggleSubjectPublish = viewModel::requestToggleSubjectPublished,
        onMoveSubject = viewModel::moveSubject,
        onOpenCreateSection = viewModel::openCreateSectionDialog,
        onOpenEditSection = viewModel::openEditSectionDialog,
        onOpenSectionFiles = viewModel::openSectionFiles,
        onToggleSectionPublish = viewModel::requestToggleSectionPublished,
        onMoveSection = viewModel::moveSection,
        onOpenCreateStudyFile = viewModel::openCreateStudyFileDialog,
        onOpenEditStudyFile = viewModel::openEditStudyFileDialog,
        onToggleStudyFilePublish = viewModel::requestToggleStudyFilePublished,
        onToggleStudyFileArchive = viewModel::requestToggleStudyFileArchived,
        onMoveStudyFile = viewModel::moveStudyFile,
        onOpenFileVersions = viewModel::openFileVersionsManager,
        onDismissFileVersions = viewModel::dismissFileVersionsManager,
        onRetryFileVersions = { viewModel.loadFileVersions() },
        onOpenCreateVersion = viewModel::openCreateVersionDialog,
        onOpenEditVersion = viewModel::openEditVersionDialog,
        onRequestPublishVersion = viewModel::requestPublishStudyFileVersion,
        onDismissEditor = viewModel::dismissEditorDialog,
        onSaveBranch = { editing, customId, name, order, isPublished ->
            viewModel.saveBranch(editing, customId, name, order, isPublished)
        },
        onSaveSubject = { branchId, editing, customId, name, order, isPublished ->
            viewModel.saveSubject(branchId, editing, customId, name, order, isPublished)
        },
        onSaveSection = { subjectId, editing, customId, name, order, isPublished ->
            viewModel.saveSection(subjectId, editing, customId, name, order, isPublished)
        },
        onSaveStudyFile = { sectionId, editing, customId, title, desc, order, pub ->
            viewModel.saveStudyFile(
                sectionId = sectionId,
                editing = editing,
                customId = customId,
                title = title,
                description = desc,
                order = order,
                isPublished = pub
            )
        },
        onSaveStudyFileVersion = { fileId, editing, customVersionId, versionNumber, label, notes, pub ->
            viewModel.saveStudyFileVersion(
                fileId = fileId,
                editing = editing,
                customVersionId = customVersionId,
                versionNumber = versionNumber,
                versionLabel = label,
                versionNotes = notes,
                publishAsActive = pub
            )
        },
        onConfirmAction = viewModel::confirmPendingAction,
        onDismissConfirmation = viewModel::dismissConfirmationDialog,
        modifier = modifier
    )
}

@Composable
fun AdminPortalScreenContent(
    uiState: AdminPortalUiState,
    onNavigateBack: () -> Unit,
    onRefreshSession: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onSelectTab: (AdminManagementSection) -> Unit,
    onDismissFeedback: () -> Unit,
    onRetryBranches: () -> Unit,
    onRetrySubjects: () -> Unit,
    onRetrySections: () -> Unit,
    onRetryStudyFiles: () -> Unit,
    onOpenCreateBranch: () -> Unit,
    onOpenEditBranch: (com.studentlearning.platform2026.domain.model.StudyBranch) -> Unit,
    onOpenBranchSubjects: (com.studentlearning.platform2026.domain.model.StudyBranch) -> Unit,
    onToggleBranchPublish: (com.studentlearning.platform2026.domain.model.StudyBranch) -> Unit,
    onMoveBranch: (com.studentlearning.platform2026.domain.model.StudyBranch, Boolean) -> Unit,
    onOpenCreateSubject: () -> Unit,
    onOpenEditSubject: (com.studentlearning.platform2026.domain.model.StudySubject) -> Unit,
    onOpenSubjectSections: (com.studentlearning.platform2026.domain.model.StudySubject) -> Unit,
    onToggleSubjectPublish: (com.studentlearning.platform2026.domain.model.StudySubject) -> Unit,
    onMoveSubject: (com.studentlearning.platform2026.domain.model.StudySubject, Boolean) -> Unit,
    onOpenCreateSection: () -> Unit,
    onOpenEditSection: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit,
    onOpenSectionFiles: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit,
    onToggleSectionPublish: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit,
    onMoveSection: (com.studentlearning.platform2026.domain.model.StudySection, Boolean) -> Unit,
    onOpenCreateStudyFile: () -> Unit,
    onOpenEditStudyFile: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit,
    onToggleStudyFilePublish: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit,
    onToggleStudyFileArchive: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit,
    onMoveStudyFile: (com.studentlearning.platform2026.domain.model.StudyFileMetadata, Boolean) -> Unit,
    onOpenFileVersions: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit = {},
    onDismissFileVersions: () -> Unit = {},
    onRetryFileVersions: () -> Unit = {},
    onOpenCreateVersion: () -> Unit = {},
    onOpenEditVersion: (com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata) -> Unit = {},
    onRequestPublishVersion: (com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata) -> Unit = {},
    onDismissEditor: () -> Unit,
    onSaveBranch: (com.studentlearning.platform2026.domain.model.StudyBranch?, String?, String, Int, Boolean) -> Unit,
    onSaveSubject: (String, com.studentlearning.platform2026.domain.model.StudySubject?, String?, String, Int, Boolean) -> Unit,
    onSaveSection: (String, com.studentlearning.platform2026.domain.model.StudySection?, String?, String, Int, Boolean) -> Unit,
    onSaveStudyFile: (String, com.studentlearning.platform2026.domain.model.StudyFileMetadata?, String?, String, String?, Int, Boolean) -> Unit,
    onSaveStudyFileVersion: (String, com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata?, String?, Int, String, String?, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onConfirmAction: () -> Unit,
    onDismissConfirmation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HaqibatiTopBar(
                title = stringResource(R.string.admin_portal_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (uiState.session.isSignedIn) {
                        IconButton(onClick = onRefreshSession) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.admin_refresh_session),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onSignOut) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = stringResource(R.string.admin_sign_out),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isCheckingSession -> {
                    SessionCheckingView()
                }
                !uiState.session.isSignedIn -> {
                    AdminSignInGate(
                        isSigningIn = uiState.isSigningIn,
                        errorReason = uiState.authErrorReason,
                        validationError = uiState.authValidationError,
                        onSignIn = onSignIn
                    )
                }
                !uiState.session.hasLoadedTokenClaims -> {
                    AdminSessionVerificationPendingGate(
                        email = uiState.session.email.orEmpty(),
                        errorReason = uiState.authErrorReason,
                        onRefreshSession = onRefreshSession,
                        onSignOut = onSignOut
                    )
                }
                !uiState.session.hasRecognizedAdminRole -> {
                    AdminAccessDeniedGate(
                        email = uiState.session.email.orEmpty(),
                        onRefreshSession = onRefreshSession,
                        onSignOut = onSignOut
                    )
                }
                else -> {
                    AdminDashboardContent(
                        uiState = uiState,
                        onRefreshSession = onRefreshSession,
                        onSelectTab = onSelectTab,
                        onDismissFeedback = onDismissFeedback,
                        onRetryBranches = onRetryBranches,
                        onRetrySubjects = onRetrySubjects,
                        onRetrySections = onRetrySections,
                        onRetryStudyFiles = onRetryStudyFiles,
                        onOpenCreateBranch = onOpenCreateBranch,
                        onOpenEditBranch = onOpenEditBranch,
                        onOpenBranchSubjects = onOpenBranchSubjects,
                        onToggleBranchPublish = onToggleBranchPublish,
                        onMoveBranch = onMoveBranch,
                        onOpenCreateSubject = onOpenCreateSubject,
                        onOpenEditSubject = onOpenEditSubject,
                        onOpenSubjectSections = onOpenSubjectSections,
                        onToggleSubjectPublish = onToggleSubjectPublish,
                        onMoveSubject = onMoveSubject,
                        onOpenCreateSection = onOpenCreateSection,
                        onOpenEditSection = onOpenEditSection,
                        onOpenSectionFiles = onOpenSectionFiles,
                        onToggleSectionPublish = onToggleSectionPublish,
                        onMoveSection = onMoveSection,
                        onOpenCreateStudyFile = onOpenCreateStudyFile,
                        onOpenEditStudyFile = onOpenEditStudyFile,
                        onToggleStudyFilePublish = onToggleStudyFilePublish,
                        onToggleStudyFileArchive = onToggleStudyFileArchive,
                        onMoveStudyFile = onMoveStudyFile,
                        onOpenFileVersions = onOpenFileVersions
                    )
                }
            }
        }
    }

    if (uiState.session.canPerformAdminActions) {
        uiState.versionsDialogFile?.let { file ->
            AdminFileVersionsDialog(
                file = file,
                versions = uiState.fileVersions,
                isLoading = uiState.isLoadingFileVersions,
                errorReason = uiState.fileVersionsError,
                r2BrokerStatus = uiState.r2BrokerStatus,
                canMutate = !uiState.isSubmitting,
                onDismiss = onDismissFileVersions,
                onRetry = onRetryFileVersions,
                onAddVersion = onOpenCreateVersion,
                onEditVersion = onOpenEditVersion,
                onPublishVersion = onRequestPublishVersion
            )
        }

        uiState.editorDialog?.let { dialog ->
            AdminEditorDialogHost(
                dialogState = dialog,
                isSubmitting = uiState.isSubmitting,
                r2BrokerStatus = uiState.r2BrokerStatus,
                onDismiss = onDismissEditor,
                onSaveBranch = { customId, name, order, isPublished ->
                    val editing = (dialog as? AdminEditorDialogState.BranchEditor)?.editing
                    onSaveBranch(editing, customId, name, order, isPublished)
                },
                onSaveSubject = { branchId, customId, name, order, isPublished ->
                    val editing = (dialog as? AdminEditorDialogState.SubjectEditor)?.editing
                    onSaveSubject(branchId, editing, customId, name, order, isPublished)
                },
                onSaveSection = { subjectId, customId, name, order, isPublished ->
                    val editing = (dialog as? AdminEditorDialogState.SectionEditor)?.editing
                    onSaveSection(subjectId, editing, customId, name, order, isPublished)
                },
                onSaveStudyFile = { sectionId, customId, title, desc, order, pub ->
                    val editing = (dialog as? AdminEditorDialogState.StudyFileEditor)?.editing
                    onSaveStudyFile(
                        sectionId,
                        editing,
                        customId,
                        title,
                        desc,
                        order,
                        pub
                    )
                },
                onSaveStudyFileVersion = { fileId, customVersionId, versionNumber, label, notes, pub ->
                    val editing = (dialog as? AdminEditorDialogState.StudyFileVersionEditor)?.editing
                    onSaveStudyFileVersion(
                        fileId,
                        editing,
                        customVersionId,
                        versionNumber,
                        label,
                        notes,
                        pub
                    )
                }
            )
        }

        uiState.confirmationAction?.let { pending ->
            AdminConfirmationDialogHost(
                action = pending,
                isSubmitting = uiState.isSubmitting,
                onConfirm = onConfirmAction,
                onDismiss = onDismissConfirmation
            )
        }
    }
}

@Composable
private fun SessionCheckingView() {
    val spacing = HaqibatiThemeTokens.spacing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.admin_checking_session),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AdminSignInGate(
    isSigningIn: Boolean,
    errorReason: AdminFailureReason?,
    validationError: AdminValidationError?,
    onSignIn: (String, String) -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item {
            HaqibatiSurfaceCard(contentPadding = spacing.lg) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AdminPanelSettings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Text(
                            text = stringResource(R.string.admin_sign_in_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = stringResource(R.string.admin_sign_in_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.admin_email_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.admin_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorReason != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = resolveAdminErrorText(errorReason, validationError),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(spacing.sm)
                            )
                        }
                    }

                    Button(
                        onClick = { onSignIn(email, password) },
                        enabled = !isSigningIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(spacing.xs))
                        }
                        Text(text = stringResource(R.string.admin_sign_in_action))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = stringResource(R.string.admin_security_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminAccessDeniedGate(
    email: String,
    onRefreshSession: () -> Unit,
    onSignOut: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        HaqibatiSurfaceCard(contentPadding = spacing.xl) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.admin_access_denied_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.admin_access_denied_message, email),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = onRefreshSession,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.admin_refresh_session))
                    }
                    Button(
                        onClick = onSignOut,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.admin_sign_out))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminSessionVerificationPendingGate(
    email: String,
    errorReason: AdminFailureReason?,
    onRefreshSession: () -> Unit,
    onSignOut: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        HaqibatiSurfaceCard(contentPadding = spacing.xl) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.admin_pending_claims_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.admin_pending_claims_message, email),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (errorReason != null) {
                    Text(
                        text = resolveAdminErrorText(errorReason, null),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Button(
                        onClick = onRefreshSession,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.admin_refresh_session))
                    }
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.admin_sign_out))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminDashboardContent(
    uiState: AdminPortalUiState,
    onRefreshSession: () -> Unit,
    onSelectTab: (AdminManagementSection) -> Unit,
    onDismissFeedback: () -> Unit,
    onRetryBranches: () -> Unit,
    onRetrySubjects: () -> Unit,
    onRetrySections: () -> Unit,
    onRetryStudyFiles: () -> Unit,
    onOpenCreateBranch: () -> Unit,
    onOpenEditBranch: (com.studentlearning.platform2026.domain.model.StudyBranch) -> Unit,
    onOpenBranchSubjects: (com.studentlearning.platform2026.domain.model.StudyBranch) -> Unit,
    onToggleBranchPublish: (com.studentlearning.platform2026.domain.model.StudyBranch) -> Unit,
    onMoveBranch: (com.studentlearning.platform2026.domain.model.StudyBranch, Boolean) -> Unit,
    onOpenCreateSubject: () -> Unit,
    onOpenEditSubject: (com.studentlearning.platform2026.domain.model.StudySubject) -> Unit,
    onOpenSubjectSections: (com.studentlearning.platform2026.domain.model.StudySubject) -> Unit,
    onToggleSubjectPublish: (com.studentlearning.platform2026.domain.model.StudySubject) -> Unit,
    onMoveSubject: (com.studentlearning.platform2026.domain.model.StudySubject, Boolean) -> Unit,
    onOpenCreateSection: () -> Unit,
    onOpenEditSection: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit,
    onOpenSectionFiles: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit,
    onToggleSectionPublish: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit,
    onMoveSection: (com.studentlearning.platform2026.domain.model.StudySection, Boolean) -> Unit,
    onOpenCreateStudyFile: () -> Unit,
    onOpenEditStudyFile: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit,
    onToggleStudyFilePublish: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit,
    onToggleStudyFileArchive: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit,
    onMoveStudyFile: (com.studentlearning.platform2026.domain.model.StudyFileMetadata, Boolean) -> Unit,
    onOpenFileVersions: (com.studentlearning.platform2026.domain.model.StudyFileMetadata) -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val tabs = AdminManagementSection.entries
    val canMutate = uiState.session.canPerformAdminActions && !uiState.isSubmitting

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = tabs.indexOf(uiState.activeTab),
            edgePadding = spacing.md
        ) {
            tabs.forEach { tab ->
                val labelRes = when (tab) {
                    AdminManagementSection.BRANCHES -> R.string.admin_tab_branches
                    AdminManagementSection.SUBJECTS -> R.string.admin_tab_subjects
                    AdminManagementSection.SECTIONS -> R.string.admin_tab_sections
                    AdminManagementSection.PDF_FILES -> R.string.admin_tab_pdf_files
                }
                Tab(
                    selected = uiState.activeTab == tab,
                    onClick = { onSelectTab(tab) },
                    text = { Text(text = stringResource(labelRes)) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            item {
                AdminSessionHeaderCard(
                    email = uiState.session.email.orEmpty(),
                    role = uiState.session.role
                )
            }

            if (!uiState.session.canPerformAdminActions) {
                item {
                    AdminStaleSessionWarningCard(
                        errorReason = uiState.authErrorReason,
                        onRefreshSession = onRefreshSession
                    )
                }
            }

            uiState.feedback?.let { feedback ->
                item {
                    Surface(
                        color = if (feedback.isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.md, vertical = spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val messageText = if (feedback.isError) {
                                resolveAdminErrorText(
                                    reason = feedback.failureReason ?: AdminFailureReason.UNKNOWN,
                                    validationError = feedback.validationError
                                )
                            } else {
                                resolveAdminSuccessText(feedback.successMessage)
                            }
                            Text(
                                text = messageText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (feedback.isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismissFeedback) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.admin_action_cancel),
                                    tint = if (feedback.isError) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    }
                                )
                            }
                        }
                    }
                }
            }

            when (uiState.activeTab) {
                AdminManagementSection.BRANCHES -> {
                    item {
                        SectionActionHeader(
                            contextText = null,
                            addLabel = stringResource(R.string.admin_add_branch),
                            addEnabled = canMutate,
                            onAddClick = onOpenCreateBranch
                        )
                    }
                    when {
                        uiState.isLoadingBranches -> item { AdminLoadingBox() }
                        uiState.branchesError != null -> item {
                            AdminErrorCard(reason = uiState.branchesError, onRetry = onRetryBranches)
                        }
                        uiState.branches.isEmpty() -> item {
                            AdminEmptyCard(message = stringResource(R.string.admin_empty_branches))
                        }
                        else -> {
                            itemsIndexed(uiState.branches, key = { _, item -> item.id }) { index, branch ->
                                AdminCatalogItemCard(
                                    title = branch.name,
                                    referenceBadge = stringResource(R.string.admin_reference_badge, branch.referencePath),
                                    order = branch.order,
                                    isPublished = branch.isPublished,
                                    canMoveUp = index > 0 && canMutate,
                                    canMoveDown = index < uiState.branches.lastIndex && canMutate,
                                    isSubmitting = !canMutate,
                                    onMoveUp = { onMoveBranch(branch, true) },
                                    onMoveDown = { onMoveBranch(branch, false) },
                                    onEdit = { onOpenEditBranch(branch) },
                                    onTogglePublish = { onToggleBranchPublish(branch) },
                                    drillDownLabel = stringResource(R.string.admin_open_subjects),
                                    onDrillDown = { onOpenBranchSubjects(branch) }
                                )
                            }
                        }
                    }
                }

                AdminManagementSection.SUBJECTS -> {
                    val selectedBranch = uiState.selectedBranch
                    if (selectedBranch == null) {
                        item { AdminEmptyCard(message = stringResource(R.string.admin_select_branch_prompt)) }
                    } else {
                        item {
                            SectionActionHeader(
                                contextText = stringResource(R.string.admin_context_branch_label, selectedBranch.name),
                                addLabel = stringResource(R.string.admin_add_subject),
                                addEnabled = canMutate,
                                onAddClick = onOpenCreateSubject
                            )
                        }
                        when {
                            uiState.isLoadingSubjects -> item { AdminLoadingBox() }
                            uiState.subjectsError != null -> item {
                                AdminErrorCard(reason = uiState.subjectsError, onRetry = onRetrySubjects)
                            }
                            uiState.subjects.isEmpty() -> item {
                                AdminEmptyCard(message = stringResource(R.string.admin_empty_subjects))
                            }
                            else -> {
                                itemsIndexed(uiState.subjects, key = { _, item -> item.id }) { index, subject ->
                                    AdminCatalogItemCard(
                                        title = subject.name,
                                        referenceBadge = stringResource(R.string.admin_reference_badge, subject.branchReferencePath),
                                        order = subject.order,
                                        isPublished = subject.isPublished,
                                        canMoveUp = index > 0 && canMutate,
                                        canMoveDown = index < uiState.subjects.lastIndex && canMutate,
                                        isSubmitting = !canMutate,
                                        onMoveUp = { onMoveSubject(subject, true) },
                                        onMoveDown = { onMoveSubject(subject, false) },
                                        onEdit = { onOpenEditSubject(subject) },
                                        onTogglePublish = { onToggleSubjectPublish(subject) },
                                        drillDownLabel = stringResource(R.string.admin_open_sections),
                                        onDrillDown = { onOpenSubjectSections(subject) }
                                    )
                                }
                            }
                        }
                    }
                }

                AdminManagementSection.SECTIONS -> {
                    val selectedSubject = uiState.selectedSubject
                    if (selectedSubject == null) {
                        item { AdminEmptyCard(message = stringResource(R.string.admin_select_subject_prompt)) }
                    } else {
                        item {
                            SectionActionHeader(
                                contextText = stringResource(R.string.admin_context_subject_label, selectedSubject.name),
                                addLabel = stringResource(R.string.admin_add_section),
                                addEnabled = canMutate,
                                onAddClick = onOpenCreateSection
                            )
                        }
                        when {
                            uiState.isLoadingSections -> item { AdminLoadingBox() }
                            uiState.sectionsError != null -> item {
                                AdminErrorCard(reason = uiState.sectionsError, onRetry = onRetrySections)
                            }
                            uiState.sections.isEmpty() -> item {
                                AdminEmptyCard(message = stringResource(R.string.admin_empty_sections))
                            }
                            else -> {
                                itemsIndexed(uiState.sections, key = { _, item -> item.id }) { index, section ->
                                    AdminCatalogItemCard(
                                        title = section.name,
                                        referenceBadge = stringResource(R.string.admin_reference_badge, section.subjectReferencePath),
                                        order = section.order,
                                        isPublished = section.isPublished,
                                        canMoveUp = index > 0 && canMutate,
                                        canMoveDown = index < uiState.sections.lastIndex && canMutate,
                                        isSubmitting = !canMutate,
                                        onMoveUp = { onMoveSection(section, true) },
                                        onMoveDown = { onMoveSection(section, false) },
                                        onEdit = { onOpenEditSection(section) },
                                        onTogglePublish = { onToggleSectionPublish(section) },
                                        drillDownLabel = stringResource(R.string.admin_open_pdf_files),
                                        onDrillDown = { onOpenSectionFiles(section) }
                                    )
                                }
                            }
                        }
                    }
                }

                AdminManagementSection.PDF_FILES -> {
                    val selectedSection = uiState.selectedSection
                    if (selectedSection == null) {
                        item { AdminEmptyCard(message = stringResource(R.string.admin_select_section_prompt)) }
                    } else {
                        item {
                            SectionActionHeader(
                                contextText = stringResource(R.string.admin_context_section_label, selectedSection.name),
                                addLabel = stringResource(R.string.admin_add_pdf_file),
                                addEnabled = canMutate,
                                onAddClick = onOpenCreateStudyFile
                            )
                        }
                        item {
                            val r2NoticeRes = if (uiState.r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED) {
                                R.string.admin_r2_configured_notice
                            } else {
                                R.string.admin_r2_deferred_notice
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(r2NoticeRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(spacing.sm)
                                )
                            }
                        }
                        when {
                            uiState.isLoadingStudyFiles -> item { AdminLoadingBox() }
                            uiState.studyFilesError != null -> item {
                                AdminErrorCard(reason = uiState.studyFilesError, onRetry = onRetryStudyFiles)
                            }
                            uiState.studyFiles.isEmpty() -> item {
                                AdminEmptyCard(message = stringResource(R.string.admin_empty_pdf_files))
                            }
                            else -> {
                                itemsIndexed(uiState.studyFiles, key = { _, item -> item.id }) { index, file ->
                                    AdminStudyFileCard(
                                        file = file,
                                        r2BrokerStatus = uiState.r2BrokerStatus,
                                        canMoveUp = index > 0 && canMutate,
                                        canMoveDown = index < uiState.studyFiles.lastIndex && canMutate,
                                        isSubmitting = !canMutate,
                                        onMoveUp = { onMoveStudyFile(file, true) },
                                        onMoveDown = { onMoveStudyFile(file, false) },
                                        onEdit = { onOpenEditStudyFile(file) },
                                        onManageVersions = { onOpenFileVersions(file) },
                                        onTogglePublish = { onToggleStudyFilePublish(file) },
                                        onToggleArchive = { onToggleStudyFileArchive(file) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminSessionHeaderCard(
    email: String,
    role: AdminRole
) {
    val spacing = HaqibatiThemeTokens.spacing
    val roleLabel = when (role) {
        AdminRole.OWNER -> stringResource(R.string.admin_role_owner)
        AdminRole.ADMIN -> stringResource(R.string.admin_role_admin)
        AdminRole.NONE -> ""
    }

    HaqibatiSurfaceCard(contentPadding = spacing.md) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (email.isNotBlank()) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.admin_rules_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AdminStaleSessionWarningCard(
    errorReason: AdminFailureReason?,
    onRefreshSession: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.admin_stale_claims_banner_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.admin_stale_claims_banner_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (errorReason != null) {
                Text(
                    text = resolveAdminErrorText(errorReason, null),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            OutlinedButton(onClick = onRefreshSession) {
                Text(text = stringResource(R.string.admin_refresh_session))
            }
        }
    }
}

@Composable
private fun SectionActionHeader(
    contextText: String?,
    addLabel: String,
    addEnabled: Boolean,
    onAddClick: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (!contextText.isNullOrBlank()) {
            Text(
                text = contextText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(spacing.sm))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Button(
            onClick = onAddClick,
            enabled = addEnabled
        ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = addLabel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminCatalogItemCard(
    title: String,
    referenceBadge: String,
    order: Int,
    isPublished: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isSubmitting: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onTogglePublish: () -> Unit,
    drillDownLabel: String,
    onDrillDown: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(contentPadding = spacing.md) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = referenceBadge,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(
                    label = stringResource(
                        if (isPublished) R.string.admin_status_published else R.string.admin_status_unpublished
                    ),
                    highlighted = isPublished
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                StatusChip(
                    label = stringResource(R.string.admin_order_badge, order),
                    highlighted = false
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.admin_action_move_up)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = stringResource(R.string.admin_action_move_down)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                OutlinedButton(onClick = onEdit, enabled = !isSubmitting) {
                    Icon(imageVector = Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.admin_action_edit))
                }
                OutlinedButton(onClick = onTogglePublish, enabled = !isSubmitting) {
                    Text(
                        text = stringResource(
                            if (isPublished) R.string.admin_action_unpublish else R.string.admin_action_publish
                        )
                    )
                }
                FilledTonalButton(onClick = onDrillDown) {
                    Text(text = drillDownLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminStudyFileCard(
    file: com.studentlearning.platform2026.domain.model.StudyFileMetadata,
    r2BrokerStatus: PdfBrokerConfigurationStatus = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isSubmitting: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onManageVersions: () -> Unit,
    onTogglePublish: () -> Unit,
    onToggleArchive: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(contentPadding = spacing.md) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!file.description.isNullOrBlank()) {
                        Text(
                            text = file.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.admin_reference_badge, file.sectionReferencePath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                StatusChip(
                    label = stringResource(
                        if (file.isPublished) R.string.admin_status_published else R.string.admin_status_unpublished
                    ),
                    highlighted = file.isPublished
                )
                if (file.isArchived) {
                    StatusChip(
                        label = stringResource(R.string.admin_status_archived),
                        highlighted = false
                    )
                }
                StatusChip(
                    label = stringResource(R.string.admin_order_badge, file.order),
                    highlighted = false
                )
                if (file.activeVersionLabel != null && file.activeVersionNumber != null) {
                    StatusChip(
                        label = stringResource(
                            R.string.admin_version_badge,
                            file.activeVersionLabel,
                            file.activeVersionNumber
                        ),
                        highlighted = false
                    )
                } else {
                    StatusChip(
                        label = stringResource(R.string.admin_no_active_version_badge),
                        highlighted = false
                    )
                }
            }

            val r2BadgeRes = if (r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED) {
                R.string.admin_r2_configured_badge
            } else {
                R.string.admin_r2_pending_badge
            }
            Text(
                text = stringResource(r2BadgeRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.admin_action_move_up)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = stringResource(R.string.admin_action_move_down)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                OutlinedButton(onClick = onEdit, enabled = !isSubmitting) {
                    Icon(imageVector = Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.admin_action_edit))
                }
                FilledTonalButton(onClick = onManageVersions, enabled = !isSubmitting) {
                    Text(text = stringResource(R.string.admin_manage_versions))
                }
                OutlinedButton(onClick = onTogglePublish, enabled = !isSubmitting) {
                    Text(
                        text = stringResource(
                            if (file.isPublished) R.string.admin_action_unpublish else R.string.admin_action_publish
                        )
                    )
                }
                TextButton(onClick = onToggleArchive, enabled = !isSubmitting) {
                    Icon(
                        imageVector = if (file.isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (file.isArchived) R.string.admin_action_restore else R.string.admin_action_archive
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    highlighted: Boolean
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AdminLoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AdminEmptyCard(message: String) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(contentPadding = spacing.lg) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AdminErrorCard(
    reason: AdminFailureReason,
    onRetry: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(contentPadding = spacing.lg) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = resolveAdminErrorText(reason, null),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun resolveAdminSuccessText(message: AdminSuccessMessage?): String {
    val resId = when (message) {
        AdminSuccessMessage.BRANCH_CREATED -> R.string.admin_success_branch_created
        AdminSuccessMessage.BRANCH_UPDATED -> R.string.admin_success_branch_updated
        AdminSuccessMessage.SUBJECT_CREATED -> R.string.admin_success_subject_created
        AdminSuccessMessage.SUBJECT_UPDATED -> R.string.admin_success_subject_updated
        AdminSuccessMessage.SECTION_CREATED -> R.string.admin_success_section_created
        AdminSuccessMessage.SECTION_UPDATED -> R.string.admin_success_section_updated
        AdminSuccessMessage.FILE_CREATED -> R.string.admin_success_file_created
        AdminSuccessMessage.FILE_UPDATED -> R.string.admin_success_file_updated
        AdminSuccessMessage.VERSION_CREATED -> R.string.admin_success_version_created
        AdminSuccessMessage.VERSION_UPDATED -> R.string.admin_success_version_updated
        AdminSuccessMessage.VERSION_PUBLISHED -> R.string.admin_success_version_published
        AdminSuccessMessage.PUBLISH_UPDATED -> R.string.admin_success_publish_updated
        AdminSuccessMessage.ARCHIVE_UPDATED -> R.string.admin_success_archive_updated
        AdminSuccessMessage.REORDERED -> R.string.admin_success_reordered
        null -> R.string.admin_success_branch_updated
    }
    return stringResource(resId)
}

@Composable
private fun resolveAdminErrorText(
    reason: AdminFailureReason,
    validationError: AdminValidationError?
): String = resolveAuthErrorText(reason, validationError)
