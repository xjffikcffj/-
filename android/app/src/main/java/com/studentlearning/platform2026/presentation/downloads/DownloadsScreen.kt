package com.studentlearning.platform2026.presentation.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LockOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.presentation.components.HaqibatiEmptyState
import com.studentlearning.platform2026.presentation.components.HaqibatiSectionHeader
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DownloadsScreenContent(
        uiState = uiState,
        onNavigateToSignIn = onNavigateToSignIn,
        onNavigateToSignUp = onNavigateToSignUp,
        modifier = modifier
    )
}

@Composable
fun DownloadsScreenContent(
    uiState: DownloadsUiState,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
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
                title = stringResource(R.string.downloads_title)
            )
        }

        if (!uiState.canDownloadOfflineFiles) {
            item {
                HaqibatiSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.LockOutline,
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

        if (!uiState.hasSavedFiles) {
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

@Preview(showBackground = true, locale = "ar")
@Composable
private fun DownloadsScreenPreview() {
    HaqibatiTheme {
        DownloadsScreenContent(uiState = DownloadsUiState())
    }
}
