package com.studentlearning.platform2026.presentation.account

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.components.HaqibatiTopBar
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun SignInScreen(
    viewModel: AccountViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSignUp: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SignInScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSignIn = viewModel::signIn,
        onSignOut = viewModel::signOut,
        onNavigateToSignUp = {
            viewModel.clearSignInFeedback()
            onNavigateToSignUp()
        },
        onNavigateToForgotPassword = {
            viewModel.clearSignInFeedback()
            onNavigateToForgotPassword()
        },
        modifier = modifier
    )
}

@Composable
fun SignInScreenContent(
    uiState: AccountUiState,
    onNavigateBack: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onNavigateToSignUp: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HaqibatiTopBar(
                title = stringResource(R.string.auth_sign_in_screen_title),
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
            if (uiState.session.isSignedIn) {
                item {
                    SignedInAccountConfirmationCard(
                        email = uiState.session.email.orEmpty(),
                        role = uiState.session.userRole,
                        isSigningOut = uiState.isSigningOut,
                        onContinue = onNavigateBack,
                        onSignOut = onSignOut
                    )
                }
            } else {
                item {
                    HaqibatiSurfaceCard(contentPadding = spacing.lg) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Login,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(spacing.sm))
                                Text(
                                    text = stringResource(R.string.auth_sign_in_screen_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = stringResource(R.string.auth_sign_in_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(stringResource(R.string.admin_email_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                enabled = !uiState.isSubmittingSignIn,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.admin_password_label)) },
                                visualTransformation = if (isPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            imageVector = if (isPasswordVisible) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = stringResource(
                                                if (isPasswordVisible) {
                                                    R.string.auth_toggle_hide_password
                                                } else {
                                                    R.string.auth_toggle_show_password
                                                }
                                            )
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                enabled = !uiState.isSubmittingSignIn,
                                modifier = Modifier.fillMaxWidth()
                            )

                            uiState.signInErrorReason?.let { reason ->
                                AuthFeedbackBanner(
                                    text = resolveAuthErrorText(reason, uiState.signInValidationError),
                                    isError = true
                                )
                            }

                            Button(
                                onClick = { onSignIn(email, password) },
                                enabled = !uiState.isSubmittingSignIn,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (uiState.isSubmittingSignIn) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(spacing.xs))
                                }
                                Text(text = stringResource(R.string.auth_action_sign_in))
                            }

                            TextButton(
                                onClick = onNavigateToForgotPassword,
                                enabled = !uiState.isSubmittingSignIn,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.auth_action_forgot_password))
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            OutlinedButton(
                                onClick = onNavigateToSignUp,
                                enabled = !uiState.isSubmittingSignIn,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.auth_action_go_to_sign_up))
                            }

                            TextButton(
                                onClick = onNavigateBack,
                                enabled = !uiState.isSubmittingSignIn,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.auth_action_continue_as_guest))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignedInAccountConfirmationCard(
    email: String,
    role: AppUserRole,
    isSigningOut: Boolean,
    onContinue: () -> Unit,
    onSignOut: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val roleLabel = stringResource(resolveUserRoleLabelRes(role))

    HaqibatiSurfaceCard(contentPadding = spacing.lg) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    text = stringResource(R.string.auth_sign_in_success_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = stringResource(R.string.auth_sign_in_success_message, email, roleLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.account_local_device_independence_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.auth_action_continue_to_app))
            }

            OutlinedButton(
                onClick = onSignOut,
                enabled = !isSigningOut,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.admin_sign_out))
            }
        }
    }
}

@Composable
internal fun AuthFeedbackBanner(
    text: String,
    isError: Boolean
) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.padding(HaqibatiThemeTokens.spacing.sm)
        )
    }
}

internal fun resolveUserRoleLabelRes(role: AppUserRole): Int = when (role) {
    AppUserRole.GUEST -> R.string.account_role_guest
    AppUserRole.STUDENT -> R.string.account_role_student
    AppUserRole.ADMIN -> R.string.account_role_admin
    AppUserRole.OWNER -> R.string.account_role_owner
}
