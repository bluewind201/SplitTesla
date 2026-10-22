@file:OptIn(ExperimentalFoundationApi::class)

package com.splittesla.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.pressScale
import kotlin.math.min

/**
 * A control pinned to a point on the car. [u] runs rear (0) to nose (1),
 * [v] runs driver side (0) to passenger side (1); values outside 0..1 sit
 * beside the body.
 */
class Hotspot(
    val u: Float,
    val v: Float,
    val label: String,
    val accent: Color = Neon.lime,
    val active: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

private const val ASPECT = 2.37f

/**
 * Top-down car, nose to the right, drawn in code so open doors, frunk and
 * trunk can light up red. Hotspot buttons float on top of it.
 */
@Composable
fun CarView(
    open: Set<Closure>?,
    modifier: Modifier = Modifier,
    headlights: Boolean = true,
    marginX: Float = 0.06f,
    marginY: Float = 0.16f,
    hotspots: List<Hotspot> = emptyList(),
    center: (@Composable () -> Unit)? = null,
) {
    val t = rememberInfiniteTransition(label = "car")
    val pulse by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "open")

    BoxWithConstraints(modifier) {
        val w = min(maxWidth.value * (1 - 2 * marginX), maxHeight.value * (1 - 2 * marginY) * ASPECT)
        val h = w / ASPECT
        val left = (maxWidth.value - w) / 2f
        val top = (maxHeight.value - h) / 2f

        Canvas(Modifier.fillMaxSize()) {
            val px = density
            drawCar(Rect(left * px, top * px, (left + w) * px, (top + h) * px), open.orEmpty(), pulse, headlights)
        }
        if (center != null) {
            Box(Modifier.centerAt(left + w * 0.40f, top + h * 0.5f)) { center() }
        }
        hotspots.forEach { spot ->
            Box(Modifier.centerAt(left + w * spot.u, top + h * spot.v)) { HotspotButton(spot) }
        }
    }
}

/** Places a child so its centre lands on (x, y) dp from the parent's corner. */
private fun Modifier.centerAt(x: Float, y: Float): Modifier = this
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(p.width, p.height) {
            p.place((x.dp.roundToPx() - p.width / 2), (y.dp.roundToPx() - p.height / 2))
        }
    }

@Composable
private fun HotspotButton(spot: Hotspot) {
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val t = rememberInfiniteTransition(label = "spot")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "ring")
    val shape = RoundedCornerShape(50)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .pressScale(source, 0.88f)
                .size(40.dp)
                .drawBehind {
                    // Sonar ring that keeps expanding from the dot so the control reads as tappable.
                    drawCircle(spot.accent.copy(alpha = 0.45f * (1 - ring)), radius = size.minDimension / 2f * (0.5f + ring * 0.7f), style = Stroke(2.dp.toPx()))
                    drawCircle(spot.accent.copy(alpha = if (spot.active) 0.35f else 0.15f), radius = size.minDimension / 2f)
                }
                .border(1.5.dp, spot.accent, CircleShape)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onLongClick = spot.onLongClick?.let { { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); spot.onClick() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(12.dp).background(spot.accent, CircleShape))
        }
        Text(
            spot.label,
            Modifier
                .offset(y = 4.dp)
                .background(Neon.bg.copy(alpha = 0.85f), shape)
                .border(1.dp, spot.accent.copy(alpha = 0.4f), shape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (spot.active) spot.accent else Neon.text,
        )
    }
}

// ── Drawing ──

private class CarFrame(val r: Rect) {
    fun x(u: Float) = r.left + u * r.width
    fun y(v: Float) = r.top + v * r.height
    fun p(u: Float, v: Float) = Offset(x(u), y(v))
}

private fun CarFrame.path(block: Path.(CarFrame) -> Unit): Path = Path().also { it.block(this) }
private fun Path.m(f: CarFrame, u: Float, v: Float) = moveTo(f.x(u), f.y(v))
private fun Path.l(f: CarFrame, u: Float, v: Float) = lineTo(f.x(u), f.y(v))
private fun Path.c(f: CarFrame, u1: Float, v1: Float, u2: Float, v2: Float, u3: Float, v3: Float) =
    cubicTo(f.x(u1), f.y(v1), f.x(u2), f.y(v2), f.x(u3), f.y(v3))

private fun DrawScope.drawCar(rect: Rect, open: Set<Closure>, pulse: Float, headlights: Boolean) {
    val f = CarFrame(rect)
    val stroke = 1.5.dp.toPx()

    // Floor glow under the car.
    drawOval(
        Brush.radialGradient(listOf(Neon.lime.copy(alpha = 0.10f), Color.Transparent), rect.center, rect.width * 0.6f),
        Offset(rect.left - rect.width * 0.08f, rect.top - rect.height * 0.25f),
        Size(rect.width * 1.16f, rect.height * 1.5f),
    )

    for ((u, v) in listOf(0.15f to -0.025f, 0.15f to 0.925f, 0.73f to -0.025f, 0.73f to 0.925f)) {
        drawRoundRect(Color.Black, f.p(u, v), Size(rect.width * 0.12f, rect.height * 0.10f), CornerRadius(8.dp.toPx()))
    }
    val mirrorL = f.path { m(it, 0.60f, 0.07f); l(it, 0.63f, -0.04f); l(it, 0.655f, -0.04f); l(it, 0.66f, 0.06f); close() }
    val mirrorR = f.path { m(it, 0.60f, 0.93f); l(it, 0.63f, 1.04f); l(it, 0.655f, 1.04f); l(it, 0.66f, 0.94f); close() }
    drawPath(mirrorL, Color(0xFF2A2E35))
    drawPath(mirrorR, Color(0xFF2A2E35))

    val body = f.path {
        m(it, 0.06f, 0.07f)
        c(it, 0.10f, 0.03f, 0.30f, 0.05f, 0.45f, 0.05f)
        c(it, 0.60f, 0.05f, 0.72f, 0.02f, 0.82f, 0.03f)
        c(it, 0.95f, 0.05f, 1.0f, 0.22f, 1.0f, 0.5f)
        c(it, 1.0f, 0.78f, 0.95f, 0.95f, 0.82f, 0.97f)
        c(it, 0.72f, 0.98f, 0.60f, 0.95f, 0.45f, 0.95f)
        c(it, 0.30f, 0.95f, 0.10f, 0.97f, 0.06f, 0.93f)
        c(it, 0.0f, 0.88f, 0.0f, 0.70f, 0.0f, 0.5f)
        c(it, 0.0f, 0.30f, 0.0f, 0.12f, 0.06f, 0.07f)
        close()
    }
    // Outline halo, then the paint: lighter along the spine for a rounded roof look.
    for (i in 1..3) drawPath(body, Neon.lime.copy(alpha = 0.06f), style = Stroke(stroke + i * 5.dp.toPx()))
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFF23272E), Color(0xFF3A404A), Color(0xFF23272E)), rect.top, rect.bottom))
    drawPath(body, Neon.lime.copy(alpha = 0.55f), style = Stroke(stroke))

    clipPath(body) {
        val red = Neon.red.copy(alpha = 0.35f * pulse)
        if (Closure.FRUNK in open) drawRect(red, f.p(0.84f, 0f), Size(rect.width * 0.17f, rect.height))
        if (Closure.TRUNK in open) drawRect(red, f.p(0f, 0f), Size(rect.width * 0.12f, rect.height))
        // Door panels: top band is the driver side.
        val doors = mapOf(
            Closure.FRONT_LEFT to Triple(0.43f, 0.64f, true), Closure.REAR_LEFT to Triple(0.25f, 0.43f, true),
            Closure.FRONT_RIGHT to Triple(0.43f, 0.64f, false), Closure.REAR_RIGHT to Triple(0.25f, 0.43f, false),
        )
        doors.forEach { (closure, d) ->
            if (closure in open) {
                val (u0, u1, driver) = d
                drawRect(red, f.p(u0, if (driver) 0f else 0.84f), Size(rect.width * (u1 - u0), rect.height * 0.16f))
            }
        }
    }

    val glass = f.path {
        m(it, 0.22f, 0.19f)
        l(it, 0.58f, 0.15f)
        c(it, 0.68f, 0.15f, 0.75f, 0.30f, 0.75f, 0.5f)
        c(it, 0.75f, 0.70f, 0.68f, 0.85f, 0.58f, 0.85f)
        l(it, 0.22f, 0.81f)
        c(it, 0.17f, 0.79f, 0.16f, 0.62f, 0.16f, 0.5f)
        c(it, 0.16f, 0.38f, 0.17f, 0.21f, 0.22f, 0.19f)
        close()
    }
    drawPath(glass, Brush.horizontalGradient(listOf(Color(0xFF0B1016), Color(0xFF121A24), Color(0xFF1B2633)), f.x(0.16f), f.x(0.75f)))
    drawPath(glass, Neon.strokeHi, style = Stroke(stroke))
    // Glass reflection streak.
    drawLine(Color.White.copy(alpha = 0.06f), f.p(0.30f, 0.25f), f.p(0.62f, 0.22f), 6.dp.toPx(), StrokeCap.Round)
    drawLine(Color(0xFF2A2E35), f.p(0.60f, 0.155f), f.p(0.60f, 0.845f), 5.dp.toPx())
    drawLine(Color(0xFF2A2E35), f.p(0.40f, 0.17f), f.p(0.40f, 0.83f), 5.dp.toPx())

    val seam = Color.Black.copy(alpha = 0.6f)
    for (u in listOf(0.25f, 0.43f, 0.64f)) {
        drawLine(seam, f.p(u, 0.05f), f.p(u, 0.16f), stroke)
        drawLine(seam, f.p(u, 0.84f), f.p(u, 0.95f), stroke)
    }
    drawPath(f.path { m(it, 0.84f, 0.10f); c(it, 0.88f, 0.3f, 0.88f, 0.7f, 0.84f, 0.90f) }, seam, style = Stroke(stroke))
    drawPath(f.path { m(it, 0.12f, 0.12f); c(it, 0.10f, 0.3f, 0.10f, 0.7f, 0.12f, 0.88f) }, seam, style = Stroke(stroke))

    if (headlights) {
        val beam = Brush.horizontalGradient(listOf(Color(0x40E8FBFF), Color.Transparent), f.x(1f), f.x(1.12f))
        drawRect(beam, f.p(0.98f, 0.12f), Size(rect.width * 0.14f, rect.height * 0.76f))
    }
    val lamp = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
    drawPath(f.path { m(it, 0.90f, 0.09f); c(it, 0.94f, 0.12f, 0.965f, 0.18f, 0.975f, 0.26f) }, Color(0xFFE8FBFF), style = lamp)
    drawPath(f.path { m(it, 0.90f, 0.91f); c(it, 0.94f, 0.88f, 0.965f, 0.82f, 0.975f, 0.74f) }, Color(0xFFE8FBFF), style = lamp)
    drawLine(Neon.red, f.p(0.018f, 0.24f), f.p(0.045f, 0.11f), 3.dp.toPx(), StrokeCap.Round)
    drawLine(Neon.red, f.p(0.018f, 0.76f), f.p(0.045f, 0.89f), 3.dp.toPx(), StrokeCap.Round)
    drawLine(Neon.red.copy(alpha = 0.8f), f.p(0.008f, 0.32f), f.p(0.008f, 0.68f), 2.dp.toPx(), StrokeCap.Round)

    // Open doors swing out from their front hinge.
    val swing = rect.height * 0.30f
    listOf(
        Closure.FRONT_LEFT to Pair(0.64f, true), Closure.REAR_LEFT to Pair(0.43f, true),
        Closure.FRONT_RIGHT to Pair(0.64f, false), Closure.REAR_RIGHT to Pair(0.43f, false),
    ).forEach { (closure, hinge) ->
        if (closure in open) {
            val (u, driver) = hinge
            val start = f.p(u, if (driver) 0.04f else 0.96f)
            val end = Offset(start.x - swing * 0.75f, start.y + if (driver) -swing else swing)
            drawLine(Neon.red.copy(alpha = 0.3f * pulse), start, end, 10.dp.toPx(), StrokeCap.Round)
            drawLine(Neon.red, start, end, 4.dp.toPx(), StrokeCap.Round)
        }
    }
    if (Closure.CHARGE_PORT in open) {
        drawCircle(Neon.green.copy(alpha = 0.4f * pulse), 9.dp.toPx(), f.p(0.06f, 0.10f))
        drawCircle(Neon.green, 4.dp.toPx(), f.p(0.06f, 0.10f))
    }
}
