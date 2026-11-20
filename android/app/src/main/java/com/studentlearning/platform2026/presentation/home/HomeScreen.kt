package com.studentlearning.platform2026.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Architecture
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.FirebaseReadiness
import com.studentlearning.platform2026.presentation.components.HaqibatiSectionHeader
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.navigation.MainTab
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreenContent(
        uiState = uiState,
        onNavigateToTab = onNavigateToTab,
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onNavigateToTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item {
            WelcomeHeroCard()
        }

        item {
            HaqibatiSectionHeader(
                title = stringResource(R.string.home_quick_sections_title)
            )
        }

        item {
            QuickNavigationSection(onNavigateToTab = onNavigateToTab)
        }

        item {
            FoundationOverviewCard(
                packageId = uiState.applicationPackageId
            )
        }

        uiState.firebaseReadiness?.let { readiness ->
            item {
                FirebaseReadinessCard(readiness = readiness)
            }
        }
    }
}

@Composable
private fun WelcomeHeroCard(modifier: Modifier = Modifier) {
    val spacing = HaqibatiThemeTokens.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
            ) {
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Text(
                text = stringResource(R.string.home_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )

            Text(
                text = stringResource(R.string.home_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.90f)
            )
        }
    }
}

@Composable
private fun QuickNavigationSection(
    onNavigateToTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        QuickSectionCard(
            icon = Icons.Rounded.MenuBook,
            title = stringResource(R.string.nav_library),
            subtitle = stringResource(R.string.home_quick_library_subtitle),
            onClick = { onNavigateToTab(MainTab.LIBRARY) }
        )
        QuickSectionCard(
            icon = Icons.Rounded.FolderOpen,
            title = stringResource(R.string.nav_downloads),
            subtitle = stringResource(R.string.home_quick_downloads_subtitle),
            onClick = { onNavigateToTab(MainTab.DOWNLOADS) }
        )
        QuickSectionCard(
            icon = Icons.Rounded.Settings,
            title = stringResource(R.string.nav_settings),
            subtitle = stringResource(R.string.home_quick_settings_subtitle),
            onClick = { onNavigateToTab(MainTab.SETTINGS) }
        )
    }
}

@Composable
private fun QuickSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(
        modifier = modifier,
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
                        imageVector = icon,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun FoundationOverviewCard(
    packageId: String,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = stringResource(R.string.home_foundation_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            InfoRowItem(
                icon = Icons.Rounded.Architecture,
                label = stringResource(R.string.home_architecture_label),
                value = stringResource(R.string.home_architecture_value)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            InfoRowItem(
                icon = Icons.Rounded.Language,
                label = stringResource(R.string.home_rtl_label),
                value = stringResource(R.string.home_rtl_value)
            )

            if (packageId.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                InfoRowItem(
                    icon = Icons.Rounded.School,
                    label = stringResource(R.string.home_package_id_label),
                    value = packageId
                )
            }
        }
    }
}

@Composable
private fun FirebaseReadinessCard(
    readiness: FirebaseReadiness,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    val statusContainerColor = when (readiness.status) {
        FirebaseConfigurationStatus.CONFIGURED_LOCALLY -> MaterialTheme.colorScheme.primaryContainer
        FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE -> MaterialTheme.colorScheme.secondaryContainer
        FirebaseConfigurationStatus.PROJECT_MISMATCH -> MaterialTheme.colorScheme.errorContainer
    }
    val statusContentColor = when (readiness.status) {
        FirebaseConfigurationStatus.CONFIGURED_LOCALLY -> MaterialTheme.colorScheme.onPrimaryContainer
        FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE -> MaterialTheme.colorScheme.onSecondaryContainer
        FirebaseConfigurationStatus.PROJECT_MISMATCH -> MaterialTheme.colorScheme.onErrorContainer
    }

    HaqibatiSurfaceCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.home_firebase_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            InfoRowItem(
                icon = Icons.Rounded.CloudQueue,
                label = stringResource(R.string.home_firebase_project_label),
                value = readiness.projectTarget.projectId
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            InfoRowItem(
                icon = Icons.Rounded.Language,
                label = stringResource(R.string.home_firebase_region_label),
                value = "${readiness.projectTarget.firestoreDatabaseId} • ${readiness.projectTarget.firestoreLocation}"
            )

            Surface(
                shape = MaterialTheme.shapes.small,
                color = statusContainerColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when (readiness.status) {
                        FirebaseConfigurationStatus.CONFIGURED_LOCALLY ->
                            stringResource(R.string.home_firebase_status_initialized)
                        FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE ->
                            stringResource(R.string.home_firebase_status_unconfigured)
                        FirebaseConfigurationStatus.PROJECT_MISMATCH ->
                            stringResource(R.string.home_firebase_status_mismatch)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusContentColor,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoRowItem(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
}

@Preview(showBackground = true, locale = "ar")
@Composable
private fun HomeScreenPreview() {
    HaqibatiTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                isLoading = false,
                firebaseReadiness = FirebaseReadiness(
                    status = FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE
                ),
                applicationPackageId = "com.studentlearning.platform2026"
            ),
            onNavigateToTab = {}
        )
    }
}
