package com.voicechat.agent.ui.theme

import androidx.compose.ui.graphics.Color

// Warm voice-assistant palette, following the neighbouring speech-android
// (VoxLLM) app rather than the earlier green theme: warm near-black surfaces
// with an orange accent in dark mode, warm paper with a deep-orange accent in
// light mode. The orb colours match the reference's idle/listening/thinking/
// speaking/unavailable phase language.

// region dark (primary brand look)

internal val BackgroundDark = Color(0xFF141210)
internal val SurfaceDark = Color(0xFF191614)
internal val SurfaceVariantDark = Color(0xFF272321)
internal val OutlineDark = Color(0xFF312D2A)
internal val OnBackgroundDark = Color(0xFFF8F5F0)
internal val OnSurfaceVariantDark = Color(0xFFB0A59B)
internal val PrimaryDark = Color(0xFFF1862C)
internal val PrimaryContainerDark = Color(0xFF4A2A0C)
internal val OnPrimaryContainerDark = Color(0xFFFFDCC2)
internal val ErrorDark = Color(0xFFD22C2C)

// endregion

// region light

internal val BackgroundLight = Color(0xFFFBF7F2)
internal val SurfaceLight = Color(0xFFFFFDFA)
internal val SurfaceVariantLight = Color(0xFFF1EAE1)
internal val OutlineLight = Color(0xFFE0D5C8)
internal val OnBackgroundLight = Color(0xFF211C17)
internal val OnSurfaceVariantLight = Color(0xFF6E635A)
internal val PrimaryLight = Color(0xFFB85A0E)
internal val PrimaryContainerLight = Color(0xFFFFDCC2)
internal val OnPrimaryContainerLight = Color(0xFF33160A)
internal val ErrorLight = Color(0xFFB3261E)

// endregion

/**
 * Core/glow gradient stops for the voice orb, one pair per session state.
 *
 * Idle orange, listening red, working amber, speaking green, and a neutral grey
 * when voice is unavailable — the same status-to-colour language the reference
 * app uses, so the orb reads through intensity rather than hue alone.
 */
internal val OrbIdle = Color(0xFFF08030) to Color(0xFFFFB37A)
internal val OrbListening = Color(0xFFFF4D4D) to Color(0xFFFF9A9A)
internal val OrbWorking = Color(0xFFFFB02E) to Color(0xFFFFD98A)
internal val OrbSpeaking = Color(0xFF34D39A) to Color(0xFF9FF0CF)
internal val OrbUnavailable = Color(0xFF5B6470) to Color(0xFF878F9C)
