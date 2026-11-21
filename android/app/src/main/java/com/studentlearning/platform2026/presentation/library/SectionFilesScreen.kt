package com.studentlearning.platform2026.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.DownloadStatus
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState
import com.studentlearning.platform2026.presentation.components.HaqibatiEmptyState
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun SectionFilesScreen(
    viewModel: SectionFilesViewModel,
    onOpenStudyVersion: (fileId: String, fileTitle: String, versionId: String?) -> Unit,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    onOpenStudyVersionWithLabel: ((fileId: String, fileTitle: String, versionId: String?, versionLabel: String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onForegroundResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    SectionFilesScreenContent(
        uiState = uiState,
        onRetry = viewModel::retry,
        onOpenStudyVersion = onOpenStudyVersion,
        onStartDownload = viewModel::startDownload,
        onCancelDownload = viewModel::cancelDownload,
        onRetryDownload = viewModel::retryDownload,
        onDismissGuestPrompt = viewModel::dismissGuestDownloadPrompt,
        onDismissTransientError = viewModel::dismissTransientError,
        onNavigateToSignIn = {
            viewModel.dismissGuestDownloadPrompt()
            onNavigateToSignIn()
        },
        onNavigateToSignUp = {
            viewModel.dismissGuestDownloadPrompt()
            onNavigateToSignUp()
        },
        onOpenStudyVersionWithLabel = onOpenStudyVersionWithLabel,
        modifier = modifier
    )
}

@Composable
fun SectionFilesScreenContent(
    uiState: SectionFilesUiState,
    onRetry: () -> Unit,
    onOpenStudyVersion: (fileId: String, fileTitle: String, versionId: String?) -> Unit,
    onStartDownload: (StudyFileMetadata) -> Unit = {},
    onCancelDownload: (StudyFileMetadata) -> Unit = {},
    onRetryDownload: (StudyFileMetadata) -> Unit = {},
    onDismissGuestPrompt: () -> Unit = {},
    onDismissTransientError: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    onOpenStudyVersionWithLabel: ((fileId: String, fileTitle: String, versionId: String?, versionLabel: String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    val openVersion: (String, String, String?, String?) -> Unit = { fileId, fileTitle, versionId, versionLabel ->
        if (onOpenStudyVersionWithLabel != null) {
            onOpenStudyVersionWithLabel(fileId, fileTitle, versionId, versionLabel)
        } else {
            onOpenStudyVersion(fileId, fileTitle, versionId)
        }
    }

    if (uiState is SectionFilesUiState.Content && !uiState.guestDownloadPromptFileTitle.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = onDismissGuestPrompt,
            title = {
                Text(text = stringResource(R.string.download_guest_dialog_title))
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.download_guest_dialog_message,
                        uiState.guestDownloadPromptFileTitle
                    )
                )
            },
            confirmButton = {
                Button(onClick = onNavigateToSignIn) {
                    Text(text = stringResource(R.string.download_guest_dialog_sign_in))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    TextButton(onClick = onDismissGuestPrompt) {
                        Text(text = stringResource(R.string.download_guest_dialog_dismiss))
                    }
                    OutlinedButton(onClick = onNavigateToSignUp) {
                        Text(text = stringResource(R.string.download_guest_dialog_sign_up))
                    }
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item {
            Text(
                text = stringResource(R.string.library_files_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (uiState) {
            SectionFilesUiState.Loading -> {
                item {
                    CatalogLoadingState()
                }
            }

            is SectionFilesUiState.Content -> {
                if (uiState.transientActionErrorReason != null) {
                    item(key = "transient_download_error") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = downloadFailureMessage(uiState.transientActionErrorReason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissTransientError) {
                                    Text(text = stringResource(R.string.download_guest_dialog_dismiss))
                                }
                            }
                        }
                    }
                }

                items(
                    items = uiState.files,
                    key = { file -> file.id }
                ) { file ->
                    val offlineState = uiState.offlineStates[file.id]
                    val showNewVersionBadge = file.id in uiState.newVersionBadgeFileIds
                    StudyFileCard(
                        file = file,
                        offlineState = offlineState,
                        showNewVersionBadge = showNewVersionBadge,
                        onBrowseOnline = {
                            openVersion(
                                file.id,
                                file.title,
                                file.activeVersionId,
                                file.activeVersionLabel
                            )
                        },
                        onStartDownload = { onStartDownload(file) },
                        onCancelDownload = { onCancelDownload(file) },
                        onRetryDownload = { onRetryDownload(file) },
                        onOpenLocalVersion = { savedVersion ->
                            openVersion(
                                savedVersion.fileId,
                                savedVersion.fileTitle.ifBlank { file.title },
                                savedVersion.versionId,
                                savedVersion.displayVersionLabel()
                            )
                        }
                    )
                }
            }

            SectionFilesUiState.Empty -> {
                item {
                    HaqibatiEmptyState(
                        icon = Icons.Rounded.Description,
                        title = stringResource(R.string.library_files_empty_title),
                        message = stringResource(R.string.library_files_empty_message)
                    )
                }
            }

            is SectionFilesUiState.Error -> {
                item {
                    CatalogErrorState(
                        reason = uiState.reason,
                        onRetry = onRetry
                    )
                }

                if (uiState.offlineSavedFiles.isNotEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.library_offline_fallback_banner),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(spacing.md)
                            )
                        }
                    }

                    items(
                        items = uiState.offlineSavedFiles,
                        key = { saved -> "${saved.fileId}_${saved.versionId}" }
                    ) { saved ->
                        OfflineSavedFallbackCard(
                            savedVersion = saved,
                            onClick = {
                                openVersion(
                                    saved.fileId,
                                    saved.fileTitle,
                                    saved.versionId,
                                    saved.displayVersionLabel()
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyFileCard(
    file: StudyFileMetadata,
    offlineState: StudyFileOfflineState?,
    showNewVersionBadge: Boolean,
    onBrowseOnline: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onOpenLocalVersion: (LocalDownloadedFileVersion) -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val activeSavedCopy = offlineState?.activeVersionSavedCopy
    val olderSavedVersions = offlineState?.olderSavedVersions.orEmpty()
    val activeTask = offlineState?.activeVersionDownload

    val cardClickAction: () -> Unit = when {
        activeSavedCopy != null -> {
            { onOpenLocalVersion(activeSavedCopy) }
        }
        !file.hasActiveVersion && olderSavedVersions.isNotEmpty() -> {
            { onOpenLocalVersion(olderSavedVersions.first()) }
        }
        else -> onBrowseOnline
    }

    HaqibatiSurfaceCard(
        contentPadding = spacing.md,
        onClick = cardClickAction
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (activeSavedCopy != null) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (activeSavedCopy != null) {
                                Icons.Rounded.OfflinePin
                            } else {
                                Icons.Rounded.PictureAsPdf
                            },
                            contentDescription = null,
                            tint = if (activeSavedCopy != null) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(spacing.sm))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = file.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!file.description.isNullOrBlank()) {
                        Text(
                            text = file.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val versionText = if (!file.activeVersionLabel.isNullOrBlank()) {
                        stringResource(R.string.library_file_version_badge, file.activeVersionLabel)
                    } else if (file.hasActiveVersion) {
                        stringResource(R.string.library_file_open_reader)
                    } else {
                        stringResource(R.string.library_file_awaiting_version)
                    }
                    Text(
                        text = versionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (showNewVersionBadge) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.library_file_new_version_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = spacing.sm, vertical = 4.dp)
                            )
                        }
                    }

                    if (activeSavedCopy != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = spacing.sm, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.library_file_downloaded_badge),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            if (activeSavedCopy != null) {
                Text(
                    text = formatLocalFileSize(activeSavedCopy.fileSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (file.hasActiveVersion && activeTask != null) {
                when (activeTask.status) {
                    DownloadStatus.QUEUED -> {
                        Text(
                            text = stringResource(R.string.library_file_download_queued),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }

                    DownloadStatus.DOWNLOADING -> {
                        val progressText = if (activeTask.progressPercent != null) {
                            stringResource(
                                R.string.library_file_downloading_percent,
                                activeTask.progressPercent
                            )
                        } else {
                            stringResource(R.string.library_file_downloading_indeterminate)
                        }
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (activeTask.progressPercent != null) {
                            LinearProgressIndicator(
                                progress = { activeTask.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                        }
                    }

                    DownloadStatus.FAILED -> {
                        val failureMessage = downloadFailureMessage(
                            activeTask.failureReason ?: DownloadFailureReason.UNKNOWN
                        )
                        Text(
                            text = failureMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    DownloadStatus.CANCELLED -> {
                        Text(
                            text = stringResource(R.string.library_file_download_cancelled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DownloadStatus.NOT_DOWNLOADED,
                    DownloadStatus.COMPLETED -> Unit
                }
            }

            if (file.hasActiveVersion) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        activeSavedCopy != null -> {
                            Button(
                                onClick = { onOpenLocalVersion(activeSavedCopy) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OfflinePin,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.library_file_open_offline))
                            }
                        }

                        activeTask?.status?.isInProgress == true -> {
                            OutlinedButton(
                                onClick = onBrowseOnline,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.library_file_browse_online))
                            }
                            OutlinedButton(
                                onClick = onCancelDownload,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.library_file_cancel_download))
                            }
                        }

                        activeTask?.status == DownloadStatus.FAILED -> {
                            OutlinedButton(
                                onClick = onBrowseOnline,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.library_file_browse_online))
                            }
                            Button(
                                onClick = onRetryDownload,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.library_file_retry_download))
                            }
                        }

                        else -> {
                            OutlinedButton(
                                onClick = onBrowseOnline,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.library_file_browse_online))
                            }
                            Button(
                                onClick = onStartDownload,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.library_file_download_offline))
                            }
                        }
                    }
                }
            }

            if (olderSavedVersions.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

                val firstOlder = olderSavedVersions.first()
                val olderLabel = firstOlder.displayVersionLabel()
                val noticeText = if (!file.activeVersionLabel.isNullOrBlank() && olderLabel != null) {
                    stringResource(
                        R.string.library_file_older_saved_notice,
                        olderLabel,
                        file.activeVersionLabel
                    )
                } else {
                    stringResource(R.string.library_file_older_saved_notice_fallback)
                }
                Text(
                    text = noticeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                olderSavedVersions.forEach { olderVersion ->
                    val label = olderVersion.displayVersionLabel() ?: olderVersion.versionId
                    OutlinedButton(
                        onClick = { onOpenLocalVersion(olderVersion) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.library_file_open_saved_older,
                                label
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineSavedFallbackCard(
    savedVersion: LocalDownloadedFileVersion,
    onClick: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val versionLabel = savedVersion.displayVersionLabel() ?: savedVersion.versionId

    HaqibatiSurfaceCard(
        contentPadding = spacing.md,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.OfflinePin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(spacing.sm))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = savedVersion.fileTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${stringResource(R.string.library_file_version_badge, versionLabel)} • ${formatLocalFileSize(savedVersion.fileSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = stringResource(R.string.library_file_downloaded_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = spacing.sm, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun formatLocalFileSize(bytes: Long): String {
    val kb = (bytes / 1024L).coerceAtLeast(1L)
    return if (bytes >= 1024L * 1024L) {
        stringResource(R.string.library_file_saved_size_mb, bytes / (1024.0 * 1024.0))
    } else {
        stringResource(R.string.library_file_saved_size_kb, kb)
    }
}

@Composable
private fun downloadFailureMessage(reason: DownloadFailureReason): String {
    return when (reason) {
        DownloadFailureReason.UNAUTHENTICATED_GUEST ->
            stringResource(R.string.download_error_guest)
        DownloadFailureReason.UNAUTHORIZED_ROLE ->
            stringResource(R.string.download_error_unauthorized_role)
        DownloadFailureReason.NO_PUBLISHED_VERSION ->
            stringResource(R.string.download_error_no_version)
        DownloadFailureReason.SECURE_BROKER_NOT_CONFIGURED ->
            stringResource(R.string.download_error_broker_not_configured)
        DownloadFailureReason.UNAUTHORIZED_ACCESS ->
            stringResource(R.string.download_error_unauthorized_access)
        DownloadFailureReason.NETWORK_UNAVAILABLE ->
            stringResource(R.string.download_error_network)
        DownloadFailureReason.SERVER_ERROR ->
            stringResource(R.string.download_error_server)
        DownloadFailureReason.INSUFFICIENT_STORAGE ->
            stringResource(R.string.download_error_insufficient_storage)
        DownloadFailureReason.STORAGE_WRITE_ERROR ->
            stringResource(R.string.download_error_storage_write)
        DownloadFailureReason.INTEGRITY_CHECK_FAILED ->
            stringResource(R.string.download_error_integrity)
        DownloadFailureReason.CANCELLED ->
            stringResource(R.string.download_error_cancelled)
        DownloadFailureReason.UNKNOWN ->
            stringResource(R.string.download_error_unknown)
    }
}

private fun LocalDownloadedFileVersion.displayVersionLabel(): String? {
    return versionLabel?.takeIf { it.isNotBlank() }
        ?: versionNumber?.let { "v$it" }
}
