package com.voicechat.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Brand = Color(0xFF8C9EFF)
private val BrandStrong = Color(0xFF5C6BC0)
private val Accent = Color(0xFFC77DFF)
private val AccentWarm = Color(0xFFFFB4A2)

private val DarkScheme = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF2A3565),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Accent,
    onSecondary = Color(0xFF22102F),
    secondaryContainer = Color(0xFF3C2454),
    onSecondaryContainer = Color(0xFFF1DCFF),
    tertiary = AccentWarm,
    background = Color(0xFF0B0C10),
    onBackground = Color(0xFFE7E8EE),
    surface = Color(0xFF12141A),
    onSurface = Color(0xFFE7E8EE),
    surfaceVariant = Color(0xFF1E212B),
    onSurfaceVariant = Color(0xFFC4C7D4),
    outline = Color(0xFF8A8FA3),
    outlineVariant = Color(0xFF2D3140),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5A1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = BrandStrong,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF7B4E9C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1DCFF),
    onSecondaryContainer = Color(0xFF2C0A44),
    tertiary = Color(0xFF8C4A33),
    background = Color(0xFFF8F8FC),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFEEEFF6),
    onSurfaceVariant = Color(0xFF464A57),
    outline = Color(0xFF767A88),
    outlineVariant = Color(0xFFD9DBE6),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun VoiceChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
