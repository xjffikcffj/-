package com.studentlearning.platform2026.presentation.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.presentation.account.resolveAuthErrorText
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AdminEditorDialogHost(
    dialogState: AdminEditorDialogState,
    isSubmitting: Boolean,
    r2BrokerStatus: PdfBrokerConfigurationStatus = PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION,
    onDismiss: () -> Unit,
    onSaveBranch: (customId: String?, name: String, order: Int, isPublished: Boolean) -> Unit,
    onSaveSubject: (branchId: String, customId: String?, name: String, order: Int, isPublished: Boolean) -> Unit,
    onSaveSection: (subjectId: String, customId: String?, name: String, order: Int, isPublished: Boolean) -> Unit,
    onSaveStudyFile: (
        sectionId: String,
        customId: String?,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean
    ) -> Unit,
    onSaveStudyFileVersion: (
        fileId: String,
        customVersionId: String?,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishAsActive: Boolean
    ) -> Unit = { _, _, _, _, _, _ -> }
) {
    when (dialogState) {
        is AdminEditorDialogState.BranchEditor -> {
            val editing = dialogState.editing
            CatalogEntryDialog(
                title = stringResource(
                    if (editing == null) R.string.admin_add_branch else R.string.admin_edit_branch
                ),
                contextLabel = null,
                initialName = editing?.name.orEmpty(),
                initialOrder = editing?.order ?: dialogState.suggestedOrder,
                initialPublished = editing?.isPublished ?: false,
                showCreateOnlyFields = editing == null,
                isSubmitting = isSubmitting,
                onDismiss = onDismiss,
                onConfirm = { customId, name, order, isPublished ->
                    onSaveBranch(customId, name, order, isPublished)
                }
            )
        }

        is AdminEditorDialogState.SubjectEditor -> {
            val editing = dialogState.editing
            CatalogEntryDialog(
                title = stringResource(
                    if (editing == null) R.string.admin_add_subject else R.string.admin_edit_subject
                ),
                contextLabel = stringResource(R.string.admin_context_branch_label, dialogState.branch.name),
                initialName = editing?.name.orEmpty(),
                initialOrder = editing?.order ?: dialogState.suggestedOrder,
                initialPublished = editing?.isPublished ?: false,
                showCreateOnlyFields = editing == null,
                isSubmitting = isSubmitting,
                onDismiss = onDismiss,
                onConfirm = { customId, name, order, isPublished ->
                    onSaveSubject(dialogState.branch.id, customId, name, order, isPublished)
                }
            )
        }

        is AdminEditorDialogState.SectionEditor -> {
            val editing = dialogState.editing
            CatalogEntryDialog(
                title = stringResource(
                    if (editing == null) R.string.admin_add_section else R.string.admin_edit_section
                ),
                contextLabel = stringResource(R.string.admin_context_subject_label, dialogState.subject.name),
                initialName = editing?.name.orEmpty(),
                initialOrder = editing?.order ?: dialogState.suggestedOrder,
                initialPublished = editing?.isPublished ?: false,
                showCreateOnlyFields = editing == null,
                isSubmitting = isSubmitting,
                onDismiss = onDismiss,
                onConfirm = { customId, name, order, isPublished ->
                    onSaveSection(dialogState.subject.id, customId, name, order, isPublished)
                }
            )
        }

        is AdminEditorDialogState.StudyFileEditor -> {
            StudyFileEditorDialog(
                dialogState = dialogState,
                isSubmitting = isSubmitting,
                onDismiss = onDismiss,
                onConfirm = { customId, title, description, order, isPublished ->
                    onSaveStudyFile(
                        dialogState.section.id,
                        customId,
                        title,
                        description,
                        order,
                        isPublished
                    )
                }
            )
        }

        is AdminEditorDialogState.StudyFileVersionEditor -> {
            StudyFileVersionEditorDialog(
                dialogState = dialogState,
                isSubmitting = isSubmitting,
                r2BrokerStatus = r2BrokerStatus,
                onDismiss = onDismiss,
                onConfirm = { customVersionId, versionNumber, versionLabel, versionNotes, publishAsActive ->
                    onSaveStudyFileVersion(
                        dialogState.file.id,
                        customVersionId,
                        versionNumber,
                        versionLabel,
                        versionNotes,
                        publishAsActive
                    )
                }
            )
        }
    }
}

@Composable
private fun CatalogEntryDialog(
    title: String,
    contextLabel: String?,
    initialName: String,
    initialOrder: Int,
    initialPublished: Boolean,
    showCreateOnlyFields: Boolean,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (customId: String?, name: String, order: Int, isPublished: Boolean) -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    var customId by remember(title, initialName) { mutableStateOf("") }
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    var orderText by remember(title, initialOrder) { mutableStateOf(initialOrder.toString()) }
    var isPublished by remember(title, initialPublished) { mutableStateOf(initialPublished) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                if (!contextLabel.isNullOrBlank()) {
                    Text(
                        text = contextLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (showCreateOnlyFields) {
                    OutlinedTextField(
                        value = customId,
                        onValueChange = { customId = it },
                        label = { Text(stringResource(R.string.admin_field_custom_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.admin_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = orderText,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() }) {
                            orderText = value
                        }
                    },
                    label = { Text(stringResource(R.string.admin_field_order)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showCreateOnlyFields) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.admin_field_publish_immediately),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isPublished,
                            onCheckedChange = { isPublished = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val parsedOrder = orderText.toIntOrNull() ?: -1
                    onConfirm(customId.ifBlank { null }, name, parsedOrder, isPublished)
                }
            ) {
                Text(stringResource(R.string.admin_action_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) {
                Text(stringResource(R.string.admin_action_cancel))
            }
        }
    )
}

@Composable
private fun StudyFileEditorDialog(
    dialogState: AdminEditorDialogState.StudyFileEditor,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        customId: String?,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean
    ) -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val editing = dialogState.editing
    var customId by remember(editing) { mutableStateOf("") }
    var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
    var description by remember(editing) { mutableStateOf(editing?.description.orEmpty()) }
    var orderText by remember(editing, dialogState.suggestedOrder) {
        mutableStateOf((editing?.order ?: dialogState.suggestedOrder).toString())
    }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (editing == null) R.string.admin_add_pdf_file else R.string.admin_edit_pdf_file
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.admin_context_section_label, dialogState.section.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.admin_r2_deferred_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (editing != null) {
                    Text(
                        text = stringResource(R.string.admin_file_edit_preserves_versions_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (editing.activeVersionLabel != null && editing.activeVersionNumber != null) {
                        Text(
                            text = stringResource(
                                R.string.admin_active_version_readonly,
                                editing.activeVersionLabel,
                                editing.activeVersionNumber
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (editing == null) {
                    OutlinedTextField(
                        value = customId,
                        onValueChange = { customId = it },
                        label = { Text(stringResource(R.string.admin_field_custom_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.admin_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.admin_field_description)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = orderText,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() }) {
                            orderText = value
                        }
                    },
                    label = { Text(stringResource(R.string.admin_field_order)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (editing == null) {
                    Text(
                        text = stringResource(R.string.admin_file_create_draft_only_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.admin_field_publish_immediately),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = false,
                            enabled = false,
                            onCheckedChange = {}
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val parsedOrder = orderText.toIntOrNull() ?: -1
                    onConfirm(
                        customId.ifBlank { null },
                        title,
                        description.ifBlank { null },
                        parsedOrder,
                        false
                    )
                }
            ) {
                Text(stringResource(R.string.admin_action_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) {
                Text(stringResource(R.string.admin_action_cancel))
            }
        }
    )
}

@Composable
internal fun AdminConfirmationDialogHost(
    action: AdminConfirmationAction,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val titleRes: Int
    val message: String

    when (action) {
        is AdminConfirmationAction.UnpublishBranch -> {
            titleRes = R.string.admin_confirm_unpublish_title
            message = stringResource(R.string.admin_confirm_unpublish_message, action.branch.name)
        }
        is AdminConfirmationAction.UnpublishSubject -> {
            titleRes = R.string.admin_confirm_unpublish_title
            message = stringResource(R.string.admin_confirm_unpublish_message, action.subject.name)
        }
        is AdminConfirmationAction.UnpublishSection -> {
            titleRes = R.string.admin_confirm_unpublish_title
            message = stringResource(R.string.admin_confirm_unpublish_message, action.section.name)
        }
        is AdminConfirmationAction.UnpublishStudyFile -> {
            titleRes = R.string.admin_confirm_unpublish_title
            message = stringResource(R.string.admin_confirm_unpublish_message, action.file.title)
        }
        is AdminConfirmationAction.ArchiveStudyFile -> {
            titleRes = R.string.admin_confirm_archive_title
            message = stringResource(R.string.admin_confirm_archive_message, action.file.title)
        }
        is AdminConfirmationAction.PublishStudyFileVersion -> {
            titleRes = R.string.admin_confirm_publish_version_title
            message = stringResource(
                R.string.admin_confirm_publish_version_message,
                action.version.versionLabel,
                action.file.title
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(text = stringResource(titleRes)) },
        text = { Text(text = message) },
        confirmButton = {
            Button(enabled = !isSubmitting, onClick = onConfirm) {
                Text(text = stringResource(R.string.admin_action_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) {
                Text(text = stringResource(R.string.admin_action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminFileVersionsDialog(
    file: StudyFileMetadata,
    versions: List<StudyFileVersionMetadata>,
    isLoading: Boolean,
    errorReason: AdminFailureReason?,
    r2BrokerStatus: PdfBrokerConfigurationStatus,
    canMutate: Boolean,
    uploadingVersionId: String? = null,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAddVersion: () -> Unit,
    onEditVersion: (StudyFileVersionMetadata) -> Unit,
    onUploadVersionPdf: (StudyFileVersionMetadata) -> Unit = {},
    onPublishVersion: (StudyFileVersionMetadata) -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val brokerNoticeRes = if (r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED) {
        R.string.admin_r2_upload_broker_configured_notice
    } else {
        R.string.admin_r2_upload_broker_pending_notice
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.admin_versions_dialog_title, file.title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(brokerNoticeRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(spacing.sm)
                    )
                }

                Button(
                    onClick = onAddVersion,
                    enabled = canMutate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.admin_add_version))
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    errorReason != null -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Text(
                                text = resolveAuthErrorText(errorReason, null),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = onRetry) {
                                Text(text = stringResource(R.string.action_retry))
                            }
                        }
                    }

                    versions.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.admin_versions_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        versions.forEach { version ->
                            val isActivePublished = version.isPublished &&
                                version.id == file.activeVersionId
                            val canPublishVersion = canMutate &&
                                !file.isArchived &&
                                !version.isArchived &&
                                version.hasUploadedPdfBinary &&
                                r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED
                            val statusLabelRes = when {
                                version.isArchived -> R.string.admin_status_archived
                                isActivePublished -> R.string.admin_version_status_active_published
                                version.publishedAtEpochMillis != null ->
                                    R.string.admin_version_status_archived_history
                                else -> R.string.admin_version_status_draft
                            }

                            HaqibatiSurfaceCard(contentPadding = spacing.sm) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = stringResource(
                                            R.string.admin_version_item_title,
                                            version.versionLabel,
                                            version.versionNumber
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isActivePublished) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ) {
                                        Text(
                                            text = stringResource(statusLabelRes),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isActivePublished) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }

                                    val verificationSummaryText = when {
                                        version.hasUploadedPdfBinary ->
                                            stringResource(R.string.admin_version_verification_verified)
                                        version.binaryVerificationStatus == "PENDING_UPLOAD" ->
                                            stringResource(R.string.admin_version_verification_pending_upload)
                                        r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED ->
                                            stringResource(R.string.admin_version_verification_none)
                                        else ->
                                            stringResource(R.string.admin_version_r2_pending_label)
                                    }
                                    Text(
                                        text = verificationSummaryText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (version.hasUploadedPdfBinary) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )

                                    if (version.hasUploadedPdfBinary && version.fileSizeBytes != null) {
                                        val sizeMb = version.fileSizeBytes.toDouble() / (1024.0 * 1024.0)
                                        Text(
                                            text = stringResource(
                                                R.string.admin_version_pdf_verified_badge,
                                                sizeMb
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    val checksumSha256 = version.checksumSha256?.takeIf { it.isNotBlank() }
                                    if (checksumSha256 != null) {
                                        Text(
                                            text = stringResource(
                                                R.string.admin_version_checksum_label,
                                                checksumSha256
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    val verifiedAttemptId = version.verifiedAttemptId?.takeIf { it.isNotBlank() }
                                    if (verifiedAttemptId != null) {
                                        Text(
                                            text = stringResource(
                                                R.string.admin_version_attempt_id_label,
                                                verifiedAttemptId
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    version.publishedAtEpochMillis?.let { millis ->
                                        val dateText = remember(millis) {
                                            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("ar"))
                                                .format(Date(millis))
                                        }
                                        Text(
                                            text = stringResource(
                                                R.string.admin_version_published_date_label,
                                                dateText
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!version.versionNotes.isNullOrBlank()) {
                                        Text(
                                            text = stringResource(
                                                R.string.admin_version_notes_label,
                                                version.versionNotes
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    val isUploadingThisVersion = uploadingVersionId == version.id
                                    val canUploadPdf = canMutate &&
                                        uploadingVersionId == null &&
                                        !file.isArchived &&
                                        !version.isArchived &&
                                        !isActivePublished &&
                                        r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED

                                    if (isUploadingThisVersion) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(
                                                text = stringResource(R.string.admin_uploading_verifying_pdf),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else if (!isActivePublished && !canPublishVersion) {
                                        val hintRes = if (
                                            r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED &&
                                            !version.hasUploadedPdfBinary &&
                                            !file.isArchived &&
                                            !version.isArchived
                                        ) {
                                            R.string.admin_version_waiting_upload_notice
                                        } else {
                                            R.string.admin_version_requires_pdf_notice
                                        }
                                        Text(
                                            text = stringResource(hintRes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onEditVersion(version) },
                                            enabled = canMutate && uploadingVersionId == null
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(text = stringResource(R.string.admin_action_edit))
                                        }
                                        if (!isActivePublished &&
                                            !version.isArchived &&
                                            r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED
                                        ) {
                                            FilledTonalButton(
                                                onClick = { onUploadVersionPdf(version) },
                                                enabled = canUploadPdf
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CloudUpload,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(text = stringResource(R.string.admin_action_upload_verify_pdf))
                                            }
                                        }
                                        if (!isActivePublished) {
                                            Button(
                                                onClick = { onPublishVersion(version) },
                                                enabled = canPublishVersion && uploadingVersionId == null
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        R.string.admin_action_publish_version
                                                    )
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.admin_action_close))
            }
        }
    )
}

@Composable
private fun StudyFileVersionEditorDialog(
    dialogState: AdminEditorDialogState.StudyFileVersionEditor,
    isSubmitting: Boolean,
    r2BrokerStatus: PdfBrokerConfigurationStatus,
    onDismiss: () -> Unit,
    onConfirm: (
        customVersionId: String?,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishAsActive: Boolean
    ) -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val editing = dialogState.editing
    var customVersionId by remember(editing) { mutableStateOf("") }
    var versionNumberText by remember(editing, dialogState.suggestedVersionNumber) {
        mutableStateOf((editing?.versionNumber ?: dialogState.suggestedVersionNumber).toString())
    }
    var versionLabel by remember(editing) {
        mutableStateOf(
            editing?.versionLabel ?: "${dialogState.suggestedVersionNumber}.0"
        )
    }
    var versionNotes by remember(editing) { mutableStateOf(editing?.versionNotes.orEmpty()) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text(
                text = stringResource(
                    if (editing == null) R.string.admin_add_version else R.string.admin_edit_version
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Text(
                    text = dialogState.file.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (editing == null) {
                    val noticeRes = if (r2BrokerStatus == PdfBrokerConfigurationStatus.CONFIGURED) {
                        R.string.admin_version_create_draft_workflow_notice
                    } else {
                        R.string.admin_version_create_draft_only_notice
                    }
                    Text(
                        text = stringResource(noticeRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (editing == null) {
                    OutlinedTextField(
                        value = customVersionId,
                        onValueChange = { customVersionId = it },
                        label = { Text(stringResource(R.string.admin_field_custom_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = versionNumberText,
                        onValueChange = { value ->
                            if (value.all { it.isDigit() }) {
                                versionNumberText = value
                            }
                        },
                        label = { Text(stringResource(R.string.admin_field_version_number)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = versionLabel,
                    onValueChange = { versionLabel = it },
                    label = { Text(stringResource(R.string.admin_field_version_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = versionNotes,
                    onValueChange = { versionNotes = it },
                    label = { Text(stringResource(R.string.admin_field_version_notes)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val parsedNumber = versionNumberText.toIntOrNull() ?: 0
                    onConfirm(
                        customVersionId.ifBlank { null },
                        parsedNumber,
                        versionLabel,
                        versionNotes.ifBlank { null },
                        false
                    )
                }
            ) {
                Text(text = stringResource(R.string.admin_action_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) {
                Text(text = stringResource(R.string.admin_action_cancel))
            }
        }
    )
}
