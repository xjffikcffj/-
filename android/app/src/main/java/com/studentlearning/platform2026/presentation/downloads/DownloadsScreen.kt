package com.studentlearning.platform2026.presentation.downloads

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.SwapVert
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
import com.studentlearning.platform2026.domain.model.FavoriteStudyFile
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
        onRemoveFavorite = viewModel::removeFavorite,
        onEnterReorderMode = viewModel::enterReorderMode,
        onMoveSavedVersionOrder = viewModel::moveSavedVersionInDraftOrder,
        onSaveReorder = viewModel::saveReorder,
        onCancelReorder = viewModel::cancelReorder,
        onResetCustomOrder = viewModel::resetCustomOrder,
        onOpenLocalVersionWithLabel = onOpenLocalVersionWithLabel,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreenContent(
    uiState: DownloadsUiState,
    modifier: Modifier = Modifier,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    onOpenLocalVersion: (fileId: String, fileTitle: String, versionId: String?) -> Unit = { _, _, _ -> },
    onDeleteLocalVersion: (fileId: String, versionId: String) -> Unit = { _, _ -> },
    onRemoveFavorite: (fileId: String) -> Unit = {},
    onEnterReorderMode: () -> Unit = {},
    onMoveSavedVersionOrder: (fileId: String, versionId: String, moveUp: Boolean) -> Unit = { _, _, _ -> },
    onSaveReorder: () -> Unit = {},
    onCancelReorder: () -> Unit = {},
    onResetCustomOrder: () -> Unit = {},
    onOpenLocalVersionWithLabel: ((fileId: String, fileTitle: String, versionId: String?, versionLabel: String?) -> Unit)? = null
) {
    val spacing = HaqibatiThemeTokens.spacing
    var pendingDeleteVersion by remember { mutableStateOf<LocalDownloadedFileVersion?>(null) }
    val openVersion: (String, String, String?, String?) -> Unit = { fileId, fileTitle, versionId, versionLabel ->
        if (onOpenLocalVersionWithLabel != null) {
            onOpenLocalVersionWithLabel(fileId, fileTitle, versionId, versionLabel)
        } else {
            onOpenLocalVersion(fileId, fileTitle, versionId)
        }
    }
    val openLocalVersion: (LocalDownloadedFileVersion) -> Unit = { saved ->
        val resolvedLabel = saved.versionLabel?.takeIf { it.isNotBlank() }
            ?: saved.versionNumber?.let { "v$it" }
        openVersion(saved.fileId, saved.fileTitle, saved.versionId, resolvedLabel)
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
                title = stringResource(R.string.downloads_favorites_section_title)
            )
        }

        if (uiState.favoriteFiles.isEmpty()) {
            item {
                HaqibatiSurfaceCard {
                    Text(
                        text = stringResource(R.string.downloads_favorites_empty_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(
                items = uiState.favoriteFiles,
                key = { fav -> "fav_${fav.fileId}" }
            ) { favorite ->
                val savedMatch = uiState.savedVersions.firstOrNull { it.fileId == favorite.fileId }
                FavoriteFileCard(
                    favorite = favorite,
                    isSavedLocally = savedMatch != null,
                    onOpen = {
                        if (savedMatch != null) {
                            openLocalVersion(savedMatch)
                        } else {
                            openVersion(
                                favorite.fileId,
                                favorite.title,
                                favorite.activeVersionId,
                                favorite.activeVersionLabel
                            )
                        }
                    },
                    onRemoveFavorite = { onRemoveFavorite(favorite.fileId) }
                )
            }
        }

        item {
            HaqibatiSectionHeader(
                title = stringResource(R.string.downloads_saved_files_section_title)
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

        if (uiState.savedVersions.size > 1 || uiState.isReorderMode || uiState.hasCustomOrder) {
            item(key = "downloads_reorder_controls") {
                if (uiState.isReorderMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(spacing.md),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Text(
                                text = stringResource(R.string.downloads_reorder_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                verticalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Button(onClick = onSaveReorder) {
                                    Text(text = stringResource(R.string.library_reorder_save))
                                }
                                OutlinedButton(onClick = onCancelReorder) {
                                    Text(text = stringResource(R.string.library_reorder_cancel))
                                }
                                if (uiState.hasCustomOrder) {
                                    TextButton(onClick = onResetCustomOrder) {
                                        Text(text = stringResource(R.string.library_reorder_reset))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        if (uiState.savedVersions.size > 1) {
                            OutlinedButton(onClick = onEnterReorderMode) {
                                Icon(
                                    imageVector = Icons.Rounded.SwapVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = stringResource(R.string.library_reorder_mode_action))
                            }
                        }
                        if (uiState.hasCustomOrder) {
                            TextButton(onClick = onResetCustomOrder) {
                                Text(text = stringResource(R.string.library_reorder_reset))
                            }
                        }
                    }
                }
            }
        }

        if (uiState.savedVersions.isNotEmpty()) {
            itemsIndexed(
                items = uiState.savedVersions,
                key = { _, saved -> "${saved.fileId}_${saved.versionId}" }
            ) { index, saved ->
                SavedDownloadedVersionCard(
                    savedVersion = saved,
                    isReorderMode = uiState.isReorderMode,
                    canMoveUp = index > 0,
                    canMoveDown = index < uiState.savedVersions.lastIndex,
                    onMoveUp = { onMoveSavedVersionOrder(saved.fileId, saved.versionId, true) },
                    onMoveDown = { onMoveSavedVersionOrder(saved.fileId, saved.versionId, false) },
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
private fun FavoriteFileCard(
    favorite: FavoriteStudyFile,
    isSavedLocally: Boolean,
    onOpen: () -> Unit,
    onRemoveFavorite: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(
        contentPadding = spacing.md,
        onClick = onOpen
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
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
                    text = favorite.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val subtitle = when {
                    isSavedLocally -> stringResource(R.string.library_file_downloaded_badge)
                    !favorite.activeVersionLabel.isNullOrBlank() ->
                        stringResource(R.string.library_file_version_badge, favorite.activeVersionLabel)
                    else -> stringResource(R.string.library_file_open_reader)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRemoveFavorite) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.library_favorite_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SavedDownloadedVersionCard(
    savedVersion: LocalDownloadedFileVersion,
    isReorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
        onClick = if (isReorderMode) null else onOpen
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

                if (isReorderMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = stringResource(R.string.library_reorder_move_up)
                            )
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = stringResource(R.string.library_reorder_move_down)
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.downloads_delete_local_version),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (!isReorderMode) {
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
}

@Preview(showBackground = true, locale = "ar")
@Composable
private fun DownloadsScreenPreview() {
    HaqibatiTheme {
        DownloadsScreenContent(uiState = DownloadsUiState())
    }
}
