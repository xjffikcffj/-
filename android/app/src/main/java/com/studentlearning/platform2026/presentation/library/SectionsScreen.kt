package com.studentlearning.platform2026.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.presentation.components.HaqibatiEmptyState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun SectionsScreen(
    viewModel: SectionsViewModel,
    modifier: Modifier = Modifier,
    onSelectSection: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SectionsScreenContent(
        uiState = uiState,
        onRetry = viewModel::retry,
        onSelectSection = onSelectSection,
        modifier = modifier
    )
}

@Composable
fun SectionsScreenContent(
    uiState: SectionsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectSection: (com.studentlearning.platform2026.domain.model.StudySection) -> Unit = {}
) {
    val spacing = HaqibatiThemeTokens.spacing

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item {
            Text(
                text = stringResource(R.string.library_sections_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (uiState) {
            SectionsUiState.Loading -> {
                item {
                    CatalogLoadingState()
                }
            }

            is SectionsUiState.Content -> {
                items(
                    items = uiState.sections,
                    key = { section -> section.id }
                ) { section ->
                    CatalogNavigationCard(
                        icon = Icons.Rounded.ViewList,
                        title = section.name,
                        onClick = { onSelectSection(section) }
                    )
                }
            }

            SectionsUiState.Empty -> {
                item {
                    HaqibatiEmptyState(
                        icon = Icons.Rounded.FolderCopy,
                        title = stringResource(R.string.library_sections_empty_title),
                        message = stringResource(R.string.library_sections_empty_message)
                    )
                }
            }

            is SectionsUiState.Error -> {
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

@Preview(showBackground = true, locale = "ar")
@Composable
private fun SectionsScreenEmptyPreview() {
    HaqibatiTheme {
        SectionsScreenContent(
            uiState = SectionsUiState.Empty,
            onRetry = {}
        )
    }
}
