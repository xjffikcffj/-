package com.studentlearning.platform2026.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens

@Composable
fun CatalogLoadingState(
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    HaqibatiSurfaceCard(
        modifier = modifier,
        contentPadding = spacing.xl
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = stringResource(R.string.catalog_loading_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun CatalogErrorState(
    reason: CatalogFailureReason,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = HaqibatiThemeTokens.spacing
    val icon = when (reason) {
        CatalogFailureReason.PERMISSION_DENIED -> Icons.Rounded.Lock
        CatalogFailureReason.NETWORK_UNAVAILABLE -> Icons.Rounded.CloudOff
        CatalogFailureReason.FIREBASE_NOT_CONFIGURED,
        CatalogFailureReason.BACKEND_NOT_CONFIGURED,
        CatalogFailureReason.UNKNOWN -> Icons.Rounded.WarningAmber
    }
    val message = when (reason) {
        CatalogFailureReason.FIREBASE_NOT_CONFIGURED ->
            stringResource(R.string.catalog_error_not_configured)
        CatalogFailureReason.BACKEND_NOT_CONFIGURED ->
            stringResource(R.string.catalog_error_backend_not_configured)
        CatalogFailureReason.PERMISSION_DENIED ->
            stringResource(R.string.catalog_error_permission_denied)
        CatalogFailureReason.NETWORK_UNAVAILABLE ->
            stringResource(R.string.catalog_error_network)
        CatalogFailureReason.UNKNOWN ->
            stringResource(R.string.catalog_error_unknown)
    }

    HaqibatiSurfaceCard(
        modifier = modifier,
        contentPadding = spacing.xl
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(60.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.catalog_error_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = spacing.xs)
            ) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
fun CatalogNavigationCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
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
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (onClick != null) {
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
