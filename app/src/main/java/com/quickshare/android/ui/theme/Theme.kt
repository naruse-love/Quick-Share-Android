package com.quickshare.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = QuickSharePrimaryDark,
    onPrimary = QuickShareOnPrimaryDark,
    primaryContainer = QuickSharePrimaryContainerDark,
    onPrimaryContainer = QuickShareOnPrimaryContainerDark,
    secondary = QuickShareSecondaryDark,
    onSecondary = QuickShareOnSecondaryDark,
    secondaryContainer = QuickShareSecondaryContainerDark,
    onSecondaryContainer = QuickShareOnSecondaryContainerDark,
    tertiary = QuickShareTertiaryDark,
    onTertiary = QuickShareOnTertiaryDark,
    tertiaryContainer = QuickShareTertiaryContainerDark,
    onTertiaryContainer = QuickShareOnTertiaryContainerDark,
    background = QuickShareBackgroundDark,
    onBackground = QuickShareOnBackgroundDark,
    surface = QuickShareSurfaceDark,
    onSurface = QuickShareOnSurfaceDark,
    surfaceVariant = QuickShareSurfaceVariantDark,
    onSurfaceVariant = QuickShareOnSurfaceVariantDark,
    error = QuickShareStatusError
)

private val LightColorScheme = lightColorScheme(
    primary = QuickSharePrimaryLight,
    onPrimary = QuickShareOnPrimaryLight,
    primaryContainer = QuickSharePrimaryContainerLight,
    onPrimaryContainer = QuickShareOnPrimaryContainerLight,
    secondary = QuickShareSecondaryLight,
    onSecondary = QuickShareOnSecondaryLight,
    secondaryContainer = QuickShareSecondaryContainerLight,
    onSecondaryContainer = QuickShareOnSecondaryContainerLight,
    tertiary = QuickShareTertiaryLight,
    onTertiary = QuickShareOnTertiaryLight,
    tertiaryContainer = QuickShareTertiaryContainerLight,
    onTertiaryContainer = QuickShareOnTertiaryContainerLight,
    background = QuickShareBackgroundLight,
    onBackground = QuickShareOnBackgroundLight,
    surface = QuickShareSurfaceLight,
    onSurface = QuickShareOnSurfaceLight,
    surfaceVariant = QuickShareSurfaceVariantLight,
    onSurfaceVariant = QuickShareOnSurfaceVariantLight,
    error = QuickShareStatusError
)

@Composable
fun QuickShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
