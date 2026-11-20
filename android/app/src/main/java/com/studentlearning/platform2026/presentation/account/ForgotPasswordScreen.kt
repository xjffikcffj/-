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
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.components.HaqibatiTopBar
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun ForgotPasswordScreen(
    viewModel: AccountViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ForgotPasswordScreenContent(
        uiState = uiState,
        onNavigateBack = {
            viewModel.clearPasswordResetFeedback()
            onNavigateBack()
        },
        onSendResetEmail = viewModel::sendPasswordResetEmail,
        modifier = modifier
    )
}

@Composable
fun ForgotPasswordScreenContent(
    uiState: AccountUiState,
    onNavigateBack: () -> Unit,
    onSendResetEmail: (email: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    var email by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HaqibatiTopBar(
                title = stringResource(R.string.auth_forgot_password_screen_title),
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
                HaqibatiSurfaceCard(contentPadding = spacing.lg) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.LockReset,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(spacing.sm))
                            Text(
                                text = stringResource(R.string.auth_forgot_password_screen_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = stringResource(R.string.auth_forgot_password_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(stringResource(R.string.admin_email_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            enabled = !uiState.isSubmittingPasswordReset,
                            modifier = Modifier.fillMaxWidth()
                        )

                        uiState.passwordResetErrorReason?.let { reason ->
                            AuthFeedbackBanner(
                                text = resolveAuthErrorText(reason, uiState.passwordResetValidationError),
                                isError = true
                            )
                        }

                        uiState.passwordResetEmailSentTo?.let { sentTo ->
                            AuthFeedbackBanner(
                                text = stringResource(
                                    R.string.auth_password_reset_success_message,
                                    sentTo
                                ),
                                isError = false
                            )
                        }

                        Button(
                            onClick = { onSendResetEmail(email) },
                            enabled = !uiState.isSubmittingPasswordReset,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (uiState.isSubmittingPasswordReset) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(spacing.xs))
                            }
                            Text(text = stringResource(R.string.auth_action_send_reset))
                        }

                        OutlinedButton(
                            onClick = onNavigateBack,
                            enabled = !uiState.isSubmittingPasswordReset,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.auth_action_back_to_sign_in))
                        }
                    }
                }
            }
        }
    }
}
