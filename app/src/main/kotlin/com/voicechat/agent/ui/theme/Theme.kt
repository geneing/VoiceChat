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
        primary = Green40,
        secondary = GreenGrey40,
        tertiary = Sand40,
    )

private val DarkColors =
    darkColorScheme(
        primary = Green80,
        secondary = GreenGrey80,
        tertiary = Sand80,
    )

/**
 * Material 3 theme for the app.
 *
 * Dynamic color is on by default; `minSdk` is 31, so the platform dynamic-color
 * APIs are always available and no version gate is needed.
 */
@Composable
fun VoiceAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
