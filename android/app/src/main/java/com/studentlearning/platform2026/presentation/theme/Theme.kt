package com.studentlearning.platform2026.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = OnEmeraldPrimary,
    primaryContainer = EmeraldPrimaryContainer,
    onPrimaryContainer = OnEmeraldPrimaryContainer,
    secondary = AmberSecondary,
    onSecondary = OnAmberSecondary,
    secondaryContainer = AmberSecondaryContainer,
    onSecondaryContainer = OnAmberSecondaryContainer,
    tertiary = SlateTertiary,
    onTertiary = OnSlateTertiary,
    tertiaryContainer = SlateTertiaryContainer,
    onTertiaryContainer = OnSlateTertiaryContainer,
    background = ParchmentBackground,
    onBackground = DeepInkOnBackground,
    surface = IvorySurface,
    onSurface = DeepInkOnBackground,
    surfaceVariant = WarmSandSurfaceVariant,
    onSurfaceVariant = MutedInkOnSurfaceVariant,
    outline = WarmOutline,
    outlineVariant = SoftOutlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimaryDark,
    onPrimary = OnEmeraldPrimaryDark,
    primaryContainer = EmeraldPrimaryContainerDark,
    onPrimaryContainer = OnEmeraldPrimaryContainerDark,
    secondary = AmberSecondaryDark,
    onSecondary = OnAmberSecondaryDark,
    secondaryContainer = AmberSecondaryContainerDark,
    onSecondaryContainer = OnAmberSecondaryContainerDark,
    tertiary = SlateTertiaryDark,
    onTertiary = OnSlateTertiaryDark,
    tertiaryContainer = SlateTertiaryContainerDark,
    onTertiaryContainer = OnSlateTertiaryContainerDark,
    background = DeepForestBackgroundDark,
    onBackground = LightInkOnBackgroundDark,
    surface = CharcoalSurfaceDark,
    onSurface = LightInkOnBackgroundDark,
    surfaceVariant = ElevatedSurfaceVariantDark,
    onSurfaceVariant = MutedInkOnSurfaceVariantDark,
    outline = CharcoalOutlineDark,
    outlineVariant = SubtleOutlineVariantDark
)

object HaqibatiThemeTokens {
    val spacing: HaqibatiSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalHaqibatiSpacing.current
}

@Composable
fun HaqibatiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalHaqibatiSpacing provides HaqibatiSpacing()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HaqibatiTypography,
            shapes = HaqibatiShapes,
            content = content
        )
    }
}
