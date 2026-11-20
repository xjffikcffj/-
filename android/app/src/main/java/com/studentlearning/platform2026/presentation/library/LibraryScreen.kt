package com.studentlearning.platform2026.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
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
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.presentation.components.HaqibatiEmptyState
import com.studentlearning.platform2026.presentation.components.HaqibatiSectionHeader
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onSelectBranch: (StudyBranch) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryScreenContent(
        uiState = uiState,
        onSelectBranch = onSelectBranch,
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

@Composable
fun LibraryScreenContent(
    uiState: LibraryUiState,
    onSelectBranch: (StudyBranch) -> Unit,
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
            HaqibatiSectionHeader(
                title = stringResource(R.string.library_branches_title)
            )
        }

        item {
            Text(
                text = stringResource(R.string.library_branches_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (uiState) {
            LibraryUiState.Loading -> {
                item {
                    CatalogLoadingState()
                }
            }

            is LibraryUiState.Content -> {
                items(
                    items = uiState.branches,
                    key = { branch -> branch.id }
                ) { branch ->
                    CatalogNavigationCard(
                        icon = Icons.Rounded.AccountTree,
                        title = branch.name,
                        onClick = { onSelectBranch(branch) }
                    )
                }
            }

            LibraryUiState.Empty -> {
                item {
                    HaqibatiEmptyState(
                        icon = Icons.Rounded.MenuBook,
                        title = stringResource(R.string.library_branches_empty_title),
                        message = stringResource(R.string.library_branches_empty_message)
                    )
                }
            }

            is LibraryUiState.Error -> {
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
private fun LibraryScreenErrorPreview() {
    HaqibatiTheme {
        LibraryScreenContent(
            uiState = LibraryUiState.Error(CatalogFailureReason.PERMISSION_DENIED),
            onSelectBranch = {},
            onRetry = {}
        )
    }
}
