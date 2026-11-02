package com.splittesla.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.splittesla.core.navi.Maneuver
import kotlin.math.hypot

/**
 * The turn as navigation apps draw it: a bold road line ending in a solid
 * arrowhead, with the road not taken dimmed beside it for forks, exits and
 * merges, an arch for underpasses and a broken crossing road for overpasses.
 * Drawn in code so it stays crisp at any size.
 */
@Composable
fun ManeuverIcon(maneuver: Maneuver?, color: Color, modifier: Modifier) {
    Canvas(modifier) { drawManeuver(maneuver ?: Maneuver.STRAIGHT, color) }
}

private class Pen(val scope: DrawScope, val color: Color) {
    val s = minOf(scope.size.width, scope.size.height)
    val ox = (scope.size.width - s) / 2
    val oy = (scope.size.height - s) / 2
    val width = s * 0.14f
    val dim = color.copy(alpha = 0.28f)

    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)

    fun path(block: Path.() -> Unit) = Path().apply(block)
    fun Path.m(x: Float, y: Float) = p(x, y).let { moveTo(it.x, it.y) }
    fun Path.l(x: Float, y: Float) = p(x, y).let { lineTo(it.x, it.y) }
    fun Path.q(x1: Float, y1: Float, x2: Float, y2: Float) {
        val a = p(x1, y1)
        val b = p(x2, y2)
        quadraticBezierTo(a.x, a.y, b.x, b.y)
    }
    fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val a = p(x1, y1)
        val b = p(x2, y2)
        val c = p(x3, y3)
        cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
    }
    fun Path.arc(left: Float, top: Float, right: Float, bottom: Float, start: Float, sweep: Float) =
        arcTo(Rect(p(left, top), p(right, bottom)), start, sweep, false)

    fun line(path: Path, c: Color = color, w: Float = width) =
        scope.drawPath(path, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))

    /** Draws [path] and a solid head whose base sits at its end ([ex], [ey]), pointing along ([dx], [dy]). */
    fun arrow(path: Path, ex: Float, ey: Float, dx: Float, dy: Float) {
        line(path)
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val base = p(ex, ey)
        val headLength = s * 0.26f
        val half = s * 0.21f
        val tip = Offset(base.x + ux * headLength, base.y + uy * headLength)
        val head = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x - uy * half, base.y + ux * half)
            lineTo(base.x + uy * half, base.y - ux * half)
            close()
        }
        scope.drawPath(head, color)
        // Round the joint between line and head.
        scope.drawCircle(color, width / 2, base)
    }
}

private fun DrawScope.drawManeuver(kind: Maneuver, color: Color) {
    val pen = Pen(this, color)
    with(pen) {
        // Right-hand shapes are the left ones mirrored.
        fun x(v: Float, mirror: Boolean) = if (mirror) 1f - v else v
        when (kind) {
            Maneuver.STRAIGHT -> arrow(path { m(0.5f, 0.94f); l(0.5f, 0.34f) }, 0.5f, 0.34f, 0f, -1f)
            Maneuver.LEFT, Maneuver.RIGHT -> {
                val r = kind == Maneuver.RIGHT
                arrow(
                    path { m(x(0.62f, r), 0.94f); l(x(0.62f, r), 0.6f); q(x(0.62f, r), 0.42f, x(0.44f, r), 0.42f); l(x(0.4f, r), 0.42f) },
                    x(0.4f, r), 0.42f, if (r) 1f else -1f, 0f,
                )
            }
            Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT -> {
                val r = kind == Maneuver.SLIGHT_RIGHT
                arrow(
                    path { m(x(0.6f, r), 0.94f); l(x(0.6f, r), 0.6f); q(x(0.6f, r), 0.48f, x(0.52f, r), 0.4f); l(x(0.4f, r), 0.28f) },
                    x(0.4f, r), 0.28f, if (r) 1f else -1f, -1f,
                )
            }
            Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT -> {
                val r = kind == Maneuver.SHARP_RIGHT
                arrow(
                    path { m(x(0.7f, r), 0.94f); l(x(0.7f, r), 0.36f); q(x(0.7f, r), 0.18f, x(0.54f, r), 0.26f); l(x(0.36f, r), 0.44f) },
                    x(0.36f, r), 0.44f, if (r) 1f else -1f, 1f,
                )
            }
            Maneuver.UTURN -> arrow(
                path { m(0.68f, 0.94f); l(0.68f, 0.42f); arc(0.32f, 0.24f, 0.68f, 0.6f, 0f, -180f); l(0.32f, 0.5f) },
                0.32f, 0.5f, 0f, 1f,
            )
            Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT -> {
                val r = kind == Maneuver.KEEP_RIGHT
                // The branch not taken carries straight on, dimmed.
                line(path { m(x(0.6f, r), 0.64f); l(x(0.6f, r), 0.12f) }, dim)
                arrow(
                    path { m(x(0.6f, r), 0.94f); l(x(0.6f, r), 0.66f); c(x(0.6f, r), 0.52f, x(0.34f, r), 0.52f, x(0.34f, r), 0.38f) },
                    x(0.34f, r), 0.38f, 0f, -1f,
                )
            }
            Maneuver.EXIT_LEFT, Maneuver.EXIT_RIGHT -> {
                val r = kind == Maneuver.EXIT_RIGHT
                // The main road keeps going, dimmed; the exit ramp peels off at an angle.
                line(path { m(x(0.64f, r), 0.66f); l(x(0.64f, r), 0.08f) }, dim)
                arrow(
                    path { m(x(0.64f, r), 0.94f); l(x(0.64f, r), 0.66f); q(x(0.64f, r), 0.5f, x(0.5f, r), 0.42f); l(x(0.38f, r), 0.35f) },
                    x(0.38f, r), 0.35f, if (r) 0.8f else -0.8f, -0.6f,
                )
            }
            Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT -> {
                val r = kind == Maneuver.MERGE_RIGHT
                // Our ramp comes in from the side and joins the main road, which is dimmed below the join.
                line(path { m(x(0.4f, !r), 0.94f); l(x(0.4f, !r), 0.5f) }, dim)
                arrow(
                    path { m(x(0.2f, !r), 0.94f); c(x(0.2f, !r), 0.72f, x(0.4f, !r), 0.66f, x(0.4f, !r), 0.48f); l(x(0.4f, !r), 0.36f) },
                    x(0.4f, !r), 0.36f, 0f, -1f,
                )
            }
            Maneuver.OVERPASS -> {
                // A road crossing underneath, broken where we pass over it.
                line(path { m(0.08f, 0.62f); l(0.34f, 0.62f) }, dim, width * 0.8f)
                line(path { m(0.66f, 0.62f); l(0.92f, 0.62f) }, dim, width * 0.8f)
                arrow(path { m(0.5f, 0.94f); l(0.5f, 0.34f) }, 0.5f, 0.34f, 0f, -1f)
            }
            Maneuver.UNDERPASS -> {
                // A tunnel mouth the arrow runs into.
                line(path { m(0.18f, 0.84f); l(0.18f, 0.52f); arc(0.18f, 0.2f, 0.82f, 0.84f, 180f, 180f); l(0.82f, 0.84f) }, dim, width * 0.8f)
                arrow(path { m(0.5f, 0.94f); l(0.5f, 0.4f) }, 0.5f, 0.4f, 0f, -1f)
            }
            Maneuver.ROUNDABOUT -> {
                scope.drawCircle(dim, s * 0.18f, p(0.5f, 0.55f), style = Stroke(width * 0.8f))
                // Traffic keeps right, so the way round is anticlockwise: in at the bottom, out at the top.
                arrow(path { m(0.5f, 0.95f); l(0.5f, 0.73f); arc(0.32f, 0.37f, 0.68f, 0.73f, 90f, -180f); l(0.5f, 0.32f) }, 0.5f, 0.32f, 0f, -1f)
            }
            Maneuver.TOLL -> {
                arrow(path { m(0.5f, 0.94f); l(0.5f, 0.34f) }, 0.5f, 0.34f, 0f, -1f)
                // The toll canopy across the road.
                scope.drawRoundRect(dim, p(0.12f, 0.5f), Size(s * 0.76f, s * 0.1f), CornerRadius(s * 0.03f))
                line(path { m(0.2f, 0.6f); l(0.2f, 0.82f) }, dim, width * 0.5f)
                line(path { m(0.8f, 0.6f); l(0.8f, 0.82f) }, dim, width * 0.5f)
            }
            Maneuver.WAYPOINT -> {
                val d = path { m(0.5f, 0.18f); l(0.8f, 0.5f); l(0.5f, 0.82f); l(0.2f, 0.5f); close() }
                scope.drawPath(d, color)
            }
            Maneuver.DESTINATION -> {
                line(path { m(0.3f, 0.92f); l(0.3f, 0.12f) }, color, width * 0.7f)
                // Chequered flag.
                val cell = s * 0.12f
                for (row in 0..2) for (col in 0..3) {
                    val on = (row + col) % 2 == 0
                    scope.drawRect(if (on) color else dim, p(0.3f, 0.12f) + Offset(col * cell, row * cell), Size(cell, cell))
                }
            }
        }
    }
}
