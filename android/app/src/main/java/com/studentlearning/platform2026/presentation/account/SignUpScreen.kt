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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PersonAdd
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
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.components.HaqibatiTopBar
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun SignUpScreen(
    viewModel: AccountViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSignIn: () -> Unit,
    onAccountReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SignUpScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onCreateAccount = viewModel::createStudentAccount,
        onNavigateToSignIn = {
            viewModel.clearSignUpFeedback()
            onNavigateToSignIn()
        },
        onContinueToApp = onAccountReady,
        modifier = modifier
    )
}

@Composable
fun SignUpScreenContent(
    uiState: AccountUiState,
    onNavigateBack: () -> Unit,
    onCreateAccount: (email: String, password: String, confirmPassword: String) -> Unit,
    onNavigateToSignIn: () -> Unit,
    onContinueToApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var isConfirmPasswordVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HaqibatiTopBar(
                title = stringResource(R.string.auth_sign_up_screen_title),
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
            if (uiState.signUpSucceeded && uiState.session.isSignedIn) {
                item {
                    HaqibatiSurfaceCard(contentPadding = spacing.lg) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(spacing.sm))
                                Text(
                                    text = stringResource(R.string.auth_sign_up_success_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            AuthFeedbackBanner(
                                text = stringResource(
                                    R.string.auth_sign_up_success_message,
                                    uiState.session.email.orEmpty()
                                ),
                                isError = false
                            )

                            Text(
                                text = stringResource(R.string.account_local_device_independence_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick = onContinueToApp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.auth_action_continue_to_app))
                            }
                        }
                    }
                }
            } else {
                item {
                    HaqibatiSurfaceCard(contentPadding = spacing.lg) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.PersonAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(spacing.sm))
                                Text(
                                    text = stringResource(R.string.auth_sign_up_screen_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = stringResource(R.string.auth_sign_up_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(stringResource(R.string.admin_email_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                enabled = !uiState.isSubmittingSignUp,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.admin_password_label)) },
                                supportingText = { Text(stringResource(R.string.auth_password_rules_hint)) },
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
                                enabled = !uiState.isSubmittingSignUp,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = { Text(stringResource(R.string.auth_field_confirm_password)) },
                                visualTransformation = if (isConfirmPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { isConfirmPasswordVisible = !isConfirmPasswordVisible }) {
                                        Icon(
                                            imageVector = if (isConfirmPasswordVisible) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = stringResource(
                                                if (isConfirmPasswordVisible) {
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
                                enabled = !uiState.isSubmittingSignUp,
                                modifier = Modifier.fillMaxWidth()
                            )

                            uiState.signUpErrorReason?.let { reason ->
                                AuthFeedbackBanner(
                                    text = resolveAuthErrorText(reason, uiState.signUpValidationError),
                                    isError = true
                                )
                            }

                            Button(
                                onClick = { onCreateAccount(email, password, confirmPassword) },
                                enabled = !uiState.isSubmittingSignUp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (uiState.isSubmittingSignUp) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(spacing.xs))
                                }
                                Text(text = stringResource(R.string.auth_action_create_account))
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text(
                                text = stringResource(R.string.auth_sign_up_student_role_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedButton(
                                onClick = onNavigateToSignIn,
                                enabled = !uiState.isSubmittingSignUp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.auth_action_go_to_sign_in))
                            }

                            TextButton(
                                onClick = onNavigateBack,
                                enabled = !uiState.isSubmittingSignUp,
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
