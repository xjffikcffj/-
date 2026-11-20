package com.studentlearning.platform2026.presentation.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
internal fun AdminEditorDialogHost(
    dialogState: AdminEditorDialogState,
    isSubmitting: Boolean,
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
    ) -> Unit
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
    var isPublished by remember(editing) { mutableStateOf(editing?.isPublished ?: false) }

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
                    onConfirm(
                        customId.ifBlank { null },
                        title,
                        description.ifBlank { null },
                        parsedOrder,
                        isPublished
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
