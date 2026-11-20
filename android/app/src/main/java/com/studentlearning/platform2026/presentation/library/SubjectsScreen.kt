package com.studentlearning.platform2026.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.presentation.components.HaqibatiEmptyState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun SubjectsScreen(
    viewModel: SubjectsViewModel,
    onSelectSubject: (StudySubject) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SubjectsScreenContent(
        uiState = uiState,
        onSelectSubject = onSelectSubject,
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

@Composable
fun SubjectsScreenContent(
    uiState: SubjectsUiState,
    onSelectSubject: (StudySubject) -> Unit,
    onRetry: () -> Unit,
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
                text = stringResource(R.string.library_subjects_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (uiState) {
            SubjectsUiState.Loading -> {
                item {
                    CatalogLoadingState()
                }
            }

            is SubjectsUiState.Content -> {
                items(
                    items = uiState.subjects,
                    key = { subject -> subject.id }
                ) { subject ->
                    CatalogNavigationCard(
                        icon = Icons.Rounded.Book,
                        title = subject.name,
                        onClick = { onSelectSubject(subject) }
                    )
                }
            }

            SubjectsUiState.Empty -> {
                item {
                    HaqibatiEmptyState(
                        icon = Icons.Rounded.MenuBook,
                        title = stringResource(R.string.library_subjects_empty_title),
                        message = stringResource(R.string.library_subjects_empty_message)
                    )
                }
            }

            is SubjectsUiState.Error -> {
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
private fun SubjectsScreenEmptyPreview() {
    HaqibatiTheme {
        SubjectsScreenContent(
            uiState = SubjectsUiState.Empty,
            onSelectSubject = {},
            onRetry = {}
        )
    }
}
