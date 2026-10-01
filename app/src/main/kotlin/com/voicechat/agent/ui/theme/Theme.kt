package com.voicechat.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = SurfaceLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        background = BackgroundLight,
        onBackground = OnBackgroundLight,
        surface = SurfaceLight,
        onSurface = OnBackgroundLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        error = ErrorLight,
    )

private val DarkColors =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = BackgroundDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnBackgroundDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        error = ErrorDark,
    )

/**
 * Brand theme for the app: a warm orange-on-warm-surface scheme in place of the
 * earlier green one, following the speech-android (VoxLLM) visual language.
 *
 * Platform dynamic colour is off by default so the brand scheme is what ships;
 * pass `dynamicColor = true` to opt a build (or a preview) back into the
 * wallpaper-derived colours. `minSdk` is 31, so the dynamic-colour APIs need no
 * version gate.
 */
@Composable
fun VoiceAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
            dynamicColor -> dynamicLightColorScheme(LocalContext.current)
            darkTheme -> DarkColors
            else -> LightColors
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
