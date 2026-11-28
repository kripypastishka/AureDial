package dev.auredial.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
private fun signalPhase(active: Boolean, durationMillis: Int): State<Float>? {
    if (!active) return null
    return rememberInfiniteTransition(label = "Signal rhythm").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = "Signal phase",
    )
}

@Composable
internal fun CallSignal(modifier: Modifier, accent: Color, ringing: Boolean) {
    val phase = signalPhase(ringing, 2600)
    Canvas(modifier) {
        val hub = Offset(size.width * 0.79f, size.height * 0.34f)
        val unit = size.minDimension
        val beat = 0.5f - 0.5f * cosPhase(phase?.value ?: 0f)
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.23f), Color.Transparent), hub, unit * 0.87f))
        repeat(5) { i ->
            val radius = unit * (0.18f + i * 0.105f + beat * (0.015f + i * 0.002f))
            val circle = Size(radius * 2f, radius * 2f)
            val origin = Offset(hub.x - radius, hub.y - radius)
            drawCircle(accent.copy(alpha = 0.16f - i * 0.02f), radius, hub, style = Stroke(1.dp.toPx()))
            drawArc(accent.copy(alpha = 0.77f - i * 0.122f), 25f + i * 37f, 72f + i * 5f,
                useCenter = false, topLeft = origin, size = circle,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        }
        drawCircle(accent.copy(alpha = 0.85f), 3.dp.toPx(), hub)
    }
}

@Composable
internal fun VoiceWave(modifier: Modifier, accent: Color, playing: Boolean) {
    val phase = signalPhase(playing, 1200)
    Canvas(modifier) {
        val progress = phase?.value ?: 0f
        val strokes = 29
        val spacing = size.width / strokes
        repeat(strokes) { index ->
            val t = index.toFloat() / (strokes - 1)
            val envelope = 0.18f + 0.82f * sin(PI.toFloat() * t)
            val variation = abs(sin(index * 0.89f + 0.32f)) * if (playing) 0.67f else 0.40f
            val motion = if (playing) 0.26f * abs(sin(index * 0.54f + progress * (2 * PI).toFloat())) else 0f
            val extent = size.height * envelope * (0.08f + variation + motion) * 0.49f
            val x = (index + 0.5f) * spacing
            drawLine(accent.copy(alpha = 0.46f + 0.44f * envelope),
                Offset(x, size.height / 2f - extent), Offset(x, size.height / 2f + extent),
                strokeWidth = minOf(3.dp.toPx(), spacing * 0.56f), cap = StrokeCap.Round)
        }
    }
}

private fun cosPhase(phase: Float) = kotlin.math.cos(phase * (2.0 * PI).toFloat())
