package com.studentlearning.platform2026.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.FirebaseReadiness
import com.studentlearning.platform2026.presentation.account.AuthFeedbackBanner
import com.studentlearning.platform2026.presentation.account.resolveAuthErrorText
import com.studentlearning.platform2026.presentation.account.resolveUserRoleLabelRes
import com.studentlearning.platform2026.presentation.components.HaqibatiSectionHeader
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToAbout: () -> Unit,
    onNavigateToAdminPortal: () -> Unit,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreenContent(
        uiState = uiState,
        onToggleDarkMode = viewModel::onToggleDarkMode,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToAdminPortal = onNavigateToAdminPortal,
        onNavigateToSignIn = onNavigateToSignIn,
        onNavigateToSignUp = onNavigateToSignUp,
        onSignOut = viewModel::onSignOut,
        modifier = modifier
    )
}

@Composable
fun SettingsScreenContent(
    uiState: SettingsUiState,
    onToggleDarkMode: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAdminPortal: () -> Unit,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    val session = uiState.adminSession
    val effectiveRole = session.userRole
    val showAdminPortalEntry = !session.isSignedIn ||
        !session.hasLoadedTokenClaims ||
        session.hasRecognizedAdminRole

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item {
            HaqibatiSectionHeader(title = stringResource(R.string.settings_account_section))
        }

        item {
            HaqibatiSurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(resolveUserRoleLabelRes(effectiveRole)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (session.isSignedIn && !session.email.isNullOrBlank()) {
                                    session.email
                                } else {
                                    stringResource(R.string.account_guest_title)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Text(
                        text = stringResource(
                            when (effectiveRole) {
                                AppUserRole.GUEST -> R.string.account_guest_permissions_summary
                                AppUserRole.STUDENT -> R.string.account_student_permissions_summary
                                AppUserRole.ADMIN -> R.string.account_admin_permissions_summary
                                AppUserRole.OWNER -> R.string.account_owner_permissions_summary
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = stringResource(R.string.account_local_device_independence_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    uiState.signOutErrorReason?.let { reason ->
                        AuthFeedbackBanner(
                            text = resolveAuthErrorText(reason, null),
                            isError = true
                        )
                    }

                    if (uiState.signedOutMessageVisible) {
                        AuthFeedbackBanner(
                            text = stringResource(R.string.account_signed_out_success),
                            isError = false
                        )
                    }

                    if (!session.isSignedIn) {
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
                    } else {
                        OutlinedButton(
                            onClick = onSignOut,
                            enabled = !uiState.isSigningOut,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Text(text = stringResource(R.string.admin_sign_out))
                        }
                    }
                }
            }
        }

        item {
            HaqibatiSectionHeader(title = stringResource(R.string.settings_appearance_section))
        }

        item {
            HaqibatiSurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_dark_mode_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_dark_mode_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Switch(
                            checked = uiState.preferences.darkModeEnabled,
                            onCheckedChange = onToggleDarkMode
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_language_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_language_value),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            HaqibatiSectionHeader(title = stringResource(R.string.settings_system_section))
        }

        uiState.firebaseReadiness?.let { readiness ->
            item {
                HaqibatiSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_firebase_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            HaqibatiSurfaceCard(onClick = onNavigateToAbout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_about_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_about_subtitle),
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

        if (showAdminPortalEntry) {
            item {
                HaqibatiSectionHeader(title = stringResource(R.string.settings_admin_section))
            }

            item {
                val adminSubtitle = when {
                    !session.isSignedIn ->
                        stringResource(R.string.settings_admin_portal_signed_out_subtitle)
                    !session.hasLoadedTokenClaims ->
                        stringResource(R.string.settings_admin_portal_pending_verification_subtitle)
                    session.hasRecognizedAdminRole -> {
                        val roleLabel = when (session.role) {
                            AdminRole.OWNER -> stringResource(R.string.admin_role_owner)
                            AdminRole.ADMIN -> stringResource(R.string.admin_role_admin)
                            AdminRole.NONE -> ""
                        }
                        stringResource(R.string.settings_admin_portal_authorized_subtitle, roleLabel)
                    }
                    else -> stringResource(R.string.settings_admin_portal_unauthorized_subtitle)
                }

                HaqibatiSurfaceCard(onClick = onNavigateToAdminPortal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AdminPanelSettings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_admin_portal_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = adminSubtitle,
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
        }
    }
}

@Preview(showBackground = true, locale = "ar")
@Composable
private fun SettingsScreenPreview() {
    HaqibatiTheme {
        SettingsScreenContent(
            uiState = SettingsUiState(
                isLoading = false,
                firebaseReadiness = FirebaseReadiness(
                    status = FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE
                )
            ),
            onToggleDarkMode = {},
            onNavigateToAbout = {},
            onNavigateToAdminPortal = {}
        )
    }
}
