package com.studentlearning.platform2026.presentation.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.presentation.components.HaqibatiEmptyState
import com.studentlearning.platform2026.presentation.components.HaqibatiSectionHeader
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    onOpenLocalVersion: (fileId: String, fileTitle: String, versionId: String?) -> Unit = { _, _, _ -> },
    onOpenLocalVersionWithLabel: ((fileId: String, fileTitle: String, versionId: String?, versionLabel: String?) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DownloadsScreenContent(
        uiState = uiState,
        onNavigateToSignIn = onNavigateToSignIn,
        onNavigateToSignUp = onNavigateToSignUp,
        onOpenLocalVersion = onOpenLocalVersion,
        onDeleteLocalVersion = viewModel::deleteLocalVersion,
        onOpenLocalVersionWithLabel = onOpenLocalVersionWithLabel,
        modifier = modifier
    )
}

@Composable
fun DownloadsScreenContent(
    uiState: DownloadsUiState,
    modifier: Modifier = Modifier,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    onOpenLocalVersion: (fileId: String, fileTitle: String, versionId: String?) -> Unit = { _, _, _ -> },
    onDeleteLocalVersion: (fileId: String, versionId: String) -> Unit = { _, _ -> },
    onOpenLocalVersionWithLabel: ((fileId: String, fileTitle: String, versionId: String?, versionLabel: String?) -> Unit)? = null
) {
    val spacing = HaqibatiThemeTokens.spacing
    var pendingDeleteVersion by remember { mutableStateOf<LocalDownloadedFileVersion?>(null) }
    val openLocalVersion: (LocalDownloadedFileVersion) -> Unit = { saved ->
        val resolvedLabel = saved.versionLabel?.takeIf { it.isNotBlank() }
            ?: saved.versionNumber?.let { "v$it" }
        if (onOpenLocalVersionWithLabel != null) {
            onOpenLocalVersionWithLabel(saved.fileId, saved.fileTitle, saved.versionId, resolvedLabel)
        } else {
            onOpenLocalVersion(saved.fileId, saved.fileTitle, saved.versionId)
        }
    }

    pendingDeleteVersion?.let { target ->
        val targetVersionLabel = target.versionLabel?.takeIf { it.isNotBlank() }
            ?: target.versionNumber?.let { "v$it" }
            ?: target.versionId
        AlertDialog(
            onDismissRequest = { pendingDeleteVersion = null },
            title = {
                Text(text = stringResource(R.string.downloads_delete_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.downloads_delete_confirm_message,
                        target.fileTitle,
                        targetVersionLabel
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteLocalVersion(target.fileId, target.versionId)
                        pendingDeleteVersion = null
                    }
                ) {
                    Text(text = stringResource(R.string.downloads_delete_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteVersion = null }) {
                    Text(text = stringResource(R.string.admin_action_cancel))
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
            HaqibatiSectionHeader(
                title = stringResource(R.string.downloads_title)
            )
        }

        if (!uiState.canDownloadOfflineFiles) {
            item {
                HaqibatiSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(spacing.sm))
                            Text(
                                text = stringResource(R.string.downloads_guest_notice_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.downloads_guest_notice_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Button(
                                onClick = onNavigateToSignIn,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = stringResource(R.string.auth_action_sign_in))
                            }
                            OutlinedButton(
                                onClick = onNavigateToSignUp,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = stringResource(R.string.auth_action_create_account))
                            }
                        }
                    }
                }
            }
        }

        if (uiState.savedVersions.isNotEmpty()) {
            items(
                items = uiState.savedVersions,
                key = { saved -> "${saved.fileId}_${saved.versionId}" }
            ) { saved ->
                SavedDownloadedVersionCard(
                    savedVersion = saved,
                    onOpen = { openLocalVersion(saved) },
                    onDelete = { pendingDeleteVersion = saved }
                )
            }
        } else if (!uiState.hasSavedFiles) {
            item {
                HaqibatiEmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = stringResource(R.string.downloads_empty_title),
                    message = stringResource(R.string.downloads_empty_message)
                )
            }
        }
    }
}

@Composable
private fun SavedDownloadedVersionCard(
    savedVersion: LocalDownloadedFileVersion,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val versionLabel = savedVersion.versionLabel?.takeIf { it.isNotBlank() }
        ?: savedVersion.versionNumber?.let { "v$it" }
        ?: savedVersion.versionId
    val sizeText = if (savedVersion.fileSizeBytes >= 1024L * 1024L) {
        stringResource(R.string.library_file_saved_size_mb, savedVersion.fileSizeBytes / (1024.0 * 1024.0))
    } else {
        stringResource(
            R.string.library_file_saved_size_kb,
            (savedVersion.fileSizeBytes / 1024L).coerceAtLeast(1L)
        )
    }

    HaqibatiSurfaceCard(
        contentPadding = spacing.md,
        onClick = onOpen
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
                        text = "${stringResource(R.string.library_file_version_badge, versionLabel)} • $sizeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.downloads_delete_local_version),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth()
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
    }
}

@Preview(showBackground = true, locale = "ar")
@Composable
private fun DownloadsScreenPreview() {
    HaqibatiTheme {
        DownloadsScreenContent(uiState = DownloadsUiState())
    }
}
