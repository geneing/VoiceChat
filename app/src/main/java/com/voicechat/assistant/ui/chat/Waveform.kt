package com.voicechat.assistant.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.sin

/** Animated voice bars. Uses the real mic level when available. */
@Composable
fun Waveform(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = 21,
    colorStart: Color,
    colorEnd: Color,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    Canvas(modifier = modifier) {
        val gap = size.width / bars
        val barWidth = (gap * 0.55f).coerceAtLeast(1.5f)
        val amplitude = if (!active) 0.08f else (0.25f + level * 0.75f) * breath
        val centerY = size.height / 2f
        for (index in 0 until bars) {
            val wave = abs(sin(phase + index * 0.55f))
            val height = size.height * (0.12f + amplitude * wave * 0.85f)
            val x = index * gap + (gap - barWidth) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(colorStart, colorEnd),
                    startY = centerY - height / 2f,
                    endY = centerY + height / 2f,
                ),
                topLeft = Offset(x, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
