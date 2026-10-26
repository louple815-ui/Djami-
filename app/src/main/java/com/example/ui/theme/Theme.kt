package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DjamiColorScheme = lightColorScheme(
    primary = DjamiGoldDark,
    onPrimary = DjamiWhite,
    primaryContainer = DjamiGoldContainer,
    onPrimaryContainer = DjamiGoldDark,
    secondary = DjamiGreen,
    onSecondary = DjamiWhite,
    secondaryContainer = DjamiGreenLight,
    onSecondaryContainer = DjamiGreenDark,
    tertiary = DjamiGoldBright,
    background = DjamiBackground,
    onBackground = DjamiTextPrimary,
    surface = DjamiSurface,
    onSurface = DjamiTextPrimary,
    surfaceVariant = DjamiSurfaceVariant,
    onSurfaceVariant = DjamiTextSecondary,
    outline = DjamiBorder,
    outlineVariant = DjamiGoldBorder
)

@Composable
fun DjamiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // DJAMI adheres strictly to a clean, bright, white & gold aesthetic
    MaterialTheme(
        colorScheme = DjamiColorScheme,
        typography = Typography,
        content = content
    )
}
