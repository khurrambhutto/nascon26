package com.nascon.league.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Decorative looping football animation drawn over a banner.
 * Chalk pitch markings are static; the ball crosses left to right with three decaying bounces.
 */
@Composable
fun AnimatedPitch(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pitch")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ballProgress"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val lineColor = Color.White.copy(alpha = 0.16f)
        val lineWidth = 2.dp.toPx()
        val inset = 16.dp.toPx()
        val midX = w * 0.78f

        // 1. Static chalk pitch markings
        drawRoundRect(
            color = lineColor,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2, h - inset * 2),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = lineWidth)
        )
        drawLine(
            color = lineColor,
            start = Offset(midX, inset),
            end = Offset(midX, h - inset),
            strokeWidth = lineWidth
        )
        drawCircle(
            color = lineColor,
            radius = h * 0.32f,
            center = Offset(midX, h / 2f),
            style = Stroke(width = lineWidth)
        )
        drawCircle(
            color = lineColor,
            radius = 3.dp.toPx(),
            center = Offset(midX, h / 2f)
        )

        // 2. Ball motion: x sweeps -5% to 105%, three bounces per crossing with decaying height
        val phase = progress * 3f
        val bounceIndex = min(floor(phase).toInt(), 2)
        val arc = abs(sin(PI.toFloat() * phase))
        val heightFraction = arc * 0.6f.pow(bounceIndex)
        val maxLift = h * 0.42f
        val lift = heightFraction * maxLift
        val floorY = h * 0.72f
        val r = 11.dp.toPx()
        val x = w * (-0.05f + 1.10f * progress)
        val y = floorY - lift
        val ballCenter = Offset(x, y)

        // 4. Soft floor shadow that shrinks as the ball rises
        val squash = 1f - 0.5f * heightFraction
        val shadowW = r * 1.8f * squash
        val shadowH = r * 0.5f * squash
        drawOval(
            color = Color.Black.copy(alpha = 0.15f * squash),
            topLeft = Offset(x - shadowW, floorY - shadowH),
            size = Size(shadowW * 2, shadowH * 2)
        )

        // 5. Speed lines trailing behind the ball
        for (i in 0..2) {
            val lineY = y + (i - 1) * 7.dp.toPx()
            val startX = x - r - 6.dp.toPx() - i * 3.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.25f),
                start = Offset(startX, lineY),
                end = Offset(startX - 14.dp.toPx(), lineY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Ball body
        drawCircle(color = Color.White, radius = r, center = ballCenter)

        // 3. Spin: darker dots rotating with horizontal progress
        val spinDegrees = x / w * 720f
        rotate(degrees = spinDegrees, pivot = ballCenter) {
            val dotRadius = 2.5.dp.toPx()
            val ringRadius = 5.5.dp.toPx()
            for (k in 0..2) {
                val angle = ((k * 120.0 - 90.0) * PI / 180.0).toFloat()
                drawCircle(
                    color = Color(0x33000000),
                    radius = dotRadius,
                    center = Offset(
                        ballCenter.x + cos(angle) * ringRadius,
                        ballCenter.y + sin(angle) * ringRadius
                    )
                )
            }
        }
    }
}
