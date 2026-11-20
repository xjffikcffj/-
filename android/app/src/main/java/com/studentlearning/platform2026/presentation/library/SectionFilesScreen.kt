package com.studentlearning.platform2026.presentation.library

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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.presentation.components.HaqibatiEmptyState
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun SectionFilesScreen(
    viewModel: SectionFilesViewModel,
    onOpenStudyFile: (StudyFileMetadata) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SectionFilesScreenContent(
        uiState = uiState,
        onRetry = viewModel::retry,
        onOpenStudyFile = onOpenStudyFile,
        modifier = modifier
    )
}

@Composable
fun SectionFilesScreenContent(
    uiState: SectionFilesUiState,
    onRetry: () -> Unit,
    onOpenStudyFile: (StudyFileMetadata) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing

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
                items(
                    items = uiState.files,
                    key = { file -> file.id }
                ) { file ->
                    StudyFileCard(
                        file = file,
                        onClick = { onOpenStudyFile(file) }
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
            }
        }
    }
}

@Composable
private fun StudyFileCard(
    file: StudyFileMetadata,
    onClick: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing

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
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                val statusLabel = if (!file.activeVersionLabel.isNullOrBlank()) {
                    stringResource(R.string.library_file_version_badge, file.activeVersionLabel)
                } else if (file.hasActiveVersion) {
                    stringResource(R.string.library_file_open_reader)
                } else {
                    stringResource(R.string.library_file_awaiting_version)
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.library_file_open_reader),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
