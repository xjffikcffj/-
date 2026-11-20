package com.studentlearning.platform2026.presentation.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.studentlearning.platform2026.BuildConfig
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.components.HaqibatiTopBar
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
    applicationId: String = BuildConfig.APPLICATION_ID
) {
    val spacing = HaqibatiThemeTokens.spacing

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HaqibatiTopBar(
                title = stringResource(R.string.about_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            item {
                HaqibatiSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                HaqibatiSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        AboutMetadataItem(
                            label = stringResource(R.string.about_version_label),
                            value = versionName
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AboutMetadataItem(
                            label = stringResource(R.string.home_package_id_label),
                            value = applicationId
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AboutMetadataItem(
                            label = stringResource(R.string.home_architecture_label),
                            value = stringResource(R.string.home_architecture_value)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AboutMetadataItem(
                            label = stringResource(R.string.about_stage_label),
                            value = stringResource(R.string.about_stage_value)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutMetadataItem(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(HaqibatiThemeTokens.spacing.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview(showBackground = true, locale = "ar")
@Composable
private fun AboutScreenPreview() {
    HaqibatiTheme {
        AboutScreen(
            onNavigateBack = {},
            versionName = "0.1.0",
            applicationId = "com.studentlearning.platform2026"
        )
    }
}
