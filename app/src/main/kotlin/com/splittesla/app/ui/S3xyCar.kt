package com.splittesla.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.splittesla.app.ui.theme.Neon
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.s3xy.CommanderAction.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * What the car picture acts out for an action, and where on the car that
 * happens ([u] rear 0 to nose 1, [v] driver side 0 to passenger side 1).
 */
enum class CarMotion(val u: Float, val v: Float, val durationMs: Int = 1800) {
    HAZARD(0.5f, 0.5f, 2400), THANKS(0.5f, 0.5f, 1400), HEADLIGHTS(0.96f, 0.5f), HIGH_BEAM(0.96f, 0.5f, 1600),
    FOG(0.98f, 0.5f), BRAKE_LIGHTS(0.03f, 0.5f, 1600), CABIN_LIGHT(0.45f, 0.5f), PARTY(0.45f, 0.5f, 2400),
    FOLD_MIRRORS(0.63f, 0.0f, 2400), MIRROR_DIP(0.63f, 0.0f), MIRROR_DIM(0.63f, 1.0f),
    WINDOWS(0.45f, 0.12f, 2200), WINDOW_FL(0.53f, 0.12f), WINDOW_FR(0.53f, 0.88f),
    REAR_DEFROST(0.19f, 0.5f, 2000), WIPER_HEAT(0.74f, 0.5f, 2000), DEFROST(0.68f, 0.5f, 2000),
    SEAT_HEAT_L(0.53f, 0.32f, 2000), SEAT_HEAT_R(0.53f, 0.68f, 2000), SEAT_VENT_L(0.53f, 0.32f, 2000),
    SEAT_VENT_R(0.53f, 0.68f, 2000), REAR_SEAT_HEAT(0.33f, 0.5f, 2000), WHEEL_HEAT(0.61f, 0.32f, 2000),
    RECIRC(0.45f, 0.5f, 2000), SCREEN(0.62f, 0.5f),
    FRUNK(0.92f, 0.5f, 2400), TRUNK(0.07f, 0.5f, 2400), CHARGE_PORT(0.06f, 0.1f, 2000),
    DOOR(0.55f, 0.04f, 2400), GLOVE_BOX(0.66f, 0.7f), CHILD_LOCK(0.34f, 0.5f),
    ACCEL(0.5f, 0.5f), REGEN(0.45f, 0.5f, 2000), STEERING(0.79f, 0.5f, 2000), STOPPING(0.03f, 0.5f),
    SUSPENSION(0.5f, 0.5f, 2200), FOLLOW(1.15f, 0.5f, 2200), TRACK(0.5f, 0.5f), TRACTION(0.5f, 0.5f),
    REAR_CAMERA(0.0f, 0.5f, 2000), HORN(1.0f, 0.5f), PULSE(0.45f, 0.5f),
}

val CommanderAction.motion: CarMotion
    get() = when (this) {
        HAZARD_LIGHTS -> CarMotion.HAZARD
        THANK_YOU -> CarMotion.THANKS
        HEADLIGHTS, LIGHTS_CONTROL -> CarMotion.HEADLIGHTS
        HIGH_BEAM_STROBE -> CarMotion.HIGH_BEAM
        ALL_FOG_LIGHTS, FRONT_FOG_LIGHTS, REAR_FOG_LIGHTS -> CarMotion.FOG
        DYNAMIC_BRAKE_LIGHTS -> CarMotion.BRAKE_LIGHTS
        INTERIOR_LIGHTS, LIGHT_STRIP_BRIGHTNESS -> CarMotion.CABIN_LIGHT
        PARTY_MODE, PLAID_LIGHTS_CONTROL -> CarMotion.PARTY
        FOLD_MIRRORS -> CarMotion.FOLD_MIRRORS
        MIRRORS_DIP -> CarMotion.MIRROR_DIP
        MIRRORS_DIM -> CarMotion.MIRROR_DIM
        VENT_WINDOWS, VENT_LEFT, VENT_RIGHT, REAR_LEFT_WINDOW, REAR_RIGHT_WINDOW -> CarMotion.WINDOWS
        FRONT_LEFT_WINDOW -> CarMotion.WINDOW_FL
        FRONT_RIGHT_WINDOW -> CarMotion.WINDOW_FR
        BACK_WINDOW_HEATER -> CarMotion.REAR_DEFROST
        WIPERS_HEAT, WIPER_MODE, WIPERS_WASHER -> CarMotion.WIPER_HEAT
        HVAC_DEFOG_DEFROST -> CarMotion.DEFROST
        FRONT_SEAT_HEAT_LEFT, LEFT_SEAT_MASSAGE -> CarMotion.SEAT_HEAT_L
        FRONT_SEAT_HEAT_RIGHT, RIGHT_SEAT_MASSAGE -> CarMotion.SEAT_HEAT_R
        FRONT_SEAT_VENT_LEFT -> CarMotion.SEAT_VENT_L
        FRONT_SEAT_VENT_RIGHT -> CarMotion.SEAT_VENT_R
        REAR_SEAT_HEAT_ALL, REAR_SEAT_HEAT_LEFT, REAR_SEAT_HEAT_RIGHT, REAR_SEAT_HEAT_CENTRAL, ALL_SEAT_HEAT -> CarMotion.REAR_SEAT_HEAT
        STEERING_WHEEL_HEAT -> CarMotion.WHEEL_HEAT
        RECIRC, HVAC_BLOWER, HVAC_ON, CABIN_TEMP, CABIN_TEMP_LEFT, CABIN_TEMP_RIGHT, KEEP_CLIMATE_ON,
        DOG_MODE, CAMP_MODE, BIOWEAPON_DEFENCE, HVAC_SECOND_ROW_STATE, AC_DISABLE -> CarMotion.RECIRC
        SCREEN_TILT -> CarMotion.SCREEN
        OPEN_FRUNK -> CarMotion.FRUNK
        OPEN_TRUNK -> CarMotion.TRUNK
        CHARGE_PORT -> CarMotion.CHARGE_PORT
        OPEN_DOOR -> CarMotion.DOOR
        OPEN_GLOVE_BOX -> CarMotion.GLOVE_BOX
        CHILD_UNLOCK, UNLOCK_CAR -> CarMotion.CHILD_LOCK
        DRIVE_MODE_ACCEL, SPEED_CONTROL -> CarMotion.ACCEL
        DRIVE_MODE_REGEN -> CarMotion.REGEN
        DRIVE_MODE_STEERING -> CarMotion.STEERING
        STOPPING_MODE, PARK_BRAKE -> CarMotion.STOPPING
        SUSPENSION, RIDE_HANDLING -> CarMotion.SUSPENSION
        FOLLOW_DISTANCE -> CarMotion.FOLLOW
        TRACK_MODE, TRACK_MODE_HANDLING, TRACK_MODE_STABILITY -> CarMotion.TRACK
        TRACTION_CONTROL -> CarMotion.TRACTION
        REAR_VIEW_CAMERA -> CarMotion.REAR_CAMERA
        HONK_HORN -> CarMotion.HORN
        else -> CarMotion.PULSE
    }

/**
 * The car from above, acting out the last action: lamps flash, lids lift,
 * mirrors fold, seats glow. Each change of [play] starts [motion] again.
 * [hints] breathe softly where this page's controls act on the car, so the
 * picture says what the page is about before anything is pressed. In a tall
 * frame the car stands nose up so it fills the space.
 */
@Composable
fun S3xyCar(motion: CarMotion?, play: Int, hints: Collection<CarMotion>, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (play > 0 && motion != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(motion.durationMs, easing = LinearEasing))
        }
    }
    val t = rememberInfiniteTransition(label = "hints")
    val breathe by t.animateFloat(0.25f, 0.8f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
    BoxWithConstraints(modifier) {
        val upright = maxHeight > maxWidth * 1.2f
        // Along and across the car, in the frame's own terms once it is stood up.
        val along = if (upright) maxHeight.value else maxWidth.value
        val across = if (upright) maxWidth.value else maxHeight.value
        // Room around the body for beams, swinging doors and the car ahead,
        // and lying down, a little lower so the page title stays clear.
        val w = min(along * 0.74f, across * (if (upright) 0.66f else 0.56f) * CAR_ASPECT)
        val drop = if (upright) 0f else across * 0.06f
        val h = w / CAR_ASPECT
        val p = progress.value
        val running = motion != null && p < 1f
        Canvas(Modifier.fillMaxSize()) {
            val mid = Offset(center.x, center.y + drop * density)
            val rect = Rect(
                mid.x - w / 2f * density, mid.y - h / 2f * density,
                mid.x + w / 2f * density, mid.y + h / 2f * density,
            )
            val f = CarFrame(rect)
            rotate(if (upright) -90f else 0f, mid) {
                if (running && motion == CarMotion.SUSPENSION) drawLift(f, envelope(p))
                val fold = if (running && motion == CarMotion.FOLD_MIRRORS) envelope(p, 0.25f) else 0f
                drawCar(rect, emptySet(), 1f, headlights = false, mirrorFold = fold)
                hints.distinct().forEach { m ->
                    if (m != motion || !running) drawCircle(Neon.lime.copy(alpha = 0.35f * breathe), 5.dp.toPx(), f.p(m.u, m.v))
                }
                if (running) drawMotion(f, motion!!, p)
            }
        }
    }
}

// ── Timing helpers, all over a motion's 0..1 progress ──

/** Rises over [edge], holds, then falls back over the last [edge]. */
private fun envelope(p: Float, edge: Float = 0.2f): Float = when {
    p < edge -> p / edge
    p > 1 - edge -> (1 - p) / edge
    else -> 1f
}.coerceIn(0f, 1f)

/** [n] on/off flashes across the motion, softened at the edges. */
private fun flashes(p: Float, n: Int): Float {
    val s = sin(p * n * 2 * PI).toFloat()
    return (s * 1.6f).coerceIn(0f, 1f)
}

/** A pulse that grows outwards and fades, repeating [n] times. */
private fun ripple(p: Float, n: Int): Float = (p * n) % 1f

// ── Drawing ──

private val amber = Color(0xFFFFB320)
private val warm = Color(0xFFFF8A3D)
private val beamWhite = Color(0xFFE8FBFF)

private fun DrawScope.glow(color: Color, center: Offset, radius: Float, strength: Float) {
    if (strength <= 0f) return
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * strength), Color.Transparent), center, radius), radius, center)
    drawCircle(color.copy(alpha = strength), radius * 0.22f, center)
}

private fun DrawScope.drawLift(f: CarFrame, lift: Float) {
    // The body rising reads as a wider, softer shadow and a lime outline lifting off it.
    val r = f.r
    val grow = r.width * 0.04f * lift
    drawOval(Color.Black.copy(alpha = 0.45f), Offset(r.left - grow, r.top - grow), Size(r.width + 2 * grow, r.height + 2 * grow))
}

private fun DrawScope.drawMotion(f: CarFrame, m: CarMotion, p: Float) {
    val r = f.r
    val unit = r.height
    when (m) {
        CarMotion.HAZARD, CarMotion.THANKS -> {
            val on = flashes(p, if (m == CarMotion.HAZARD) 4 else 2)
            for ((u, v) in listOf(0.95f to 0.12f, 0.95f to 0.88f, 0.03f to 0.15f, 0.03f to 0.85f, 0.78f to 0.0f, 0.78f to 1.0f)) {
                glow(amber, f.p(u, v), unit * 0.22f, on)
            }
        }
        CarMotion.HEADLIGHTS, CarMotion.HIGH_BEAM -> {
            val on = if (m == CarMotion.HEADLIGHTS) envelope(p) else flashes(p, 3)
            val reach = if (m == CarMotion.HIGH_BEAM) 0.34f else 0.22f
            val beam = Path().apply {
                moveTo(f.x(0.97f), f.y(0.18f))
                lineTo(f.x(1f + reach), f.y(-0.15f))
                lineTo(f.x(1f + reach), f.y(1.15f))
                lineTo(f.x(0.97f), f.y(0.82f))
                close()
            }
            drawPath(beam, Brush.horizontalGradient(listOf(beamWhite.copy(alpha = 0.45f * on), Color.Transparent), f.x(0.97f), f.x(1f + reach)))
            glow(beamWhite, f.p(0.96f, 0.2f), unit * 0.12f, on)
            glow(beamWhite, f.p(0.96f, 0.8f), unit * 0.12f, on)
        }
        CarMotion.FOG -> {
            val on = envelope(p)
            glow(beamWhite, f.p(0.99f, 0.3f), unit * 0.16f, on)
            glow(beamWhite, f.p(0.99f, 0.7f), unit * 0.16f, on)
            glow(Neon.red, f.p(0.005f, 0.5f), unit * 0.16f, on)
        }
        CarMotion.BRAKE_LIGHTS, CarMotion.STOPPING -> {
            val on = if (m == CarMotion.BRAKE_LIGHTS) flashes(p, 5) else envelope(p)
            for (v in listOf(0.15f, 0.5f, 0.85f)) glow(Neon.red, f.p(0.02f, v), unit * 0.2f, on)
            if (m == CarMotion.STOPPING) wheels(f) { c -> drawCircle(Neon.red.copy(alpha = 0.7f * on), unit * 0.07f, c, style = Stroke(2.dp.toPx())) }
        }
        CarMotion.CABIN_LIGHT -> glow(Color(0xFFFFE2A8), f.p(0.45f, 0.5f), unit * 0.5f, envelope(p))
        CarMotion.PARTY -> {
            val hue = lerp(lerp(Neon.lime, Neon.cyan, (p * 3) % 1f), Color(0xFFFF4FD8), abs(sin(p * 2 * PI).toFloat()))
            glow(hue, f.p(0.45f, 0.5f), unit * 0.55f, envelope(p, 0.1f))
            for ((u, v) in listOf(0.95f to 0.12f, 0.95f to 0.88f, 0.03f to 0.15f, 0.03f to 0.85f)) glow(hue, f.p(u, v), unit * 0.14f, flashes(p, 6))
        }
        CarMotion.FOLD_MIRRORS, CarMotion.MIRROR_DIP, CarMotion.MIRROR_DIM -> {
            val color = when (m) {
                CarMotion.FOLD_MIRRORS -> Neon.lime
                CarMotion.MIRROR_DIP -> Neon.cyan
                else -> Color(0xFF7A5CFF)
            }
            val on = if (m == CarMotion.MIRROR_DIM) envelope(p) else flashes(p, 2) * 0.6f + 0.4f * envelope(p)
            glow(color, f.p(0.64f, -0.02f), unit * 0.16f, on)
            glow(color, f.p(0.64f, 1.02f), unit * 0.16f, on)
            if (m == CarMotion.MIRROR_DIP) {
                // A small arrow down past the driver's mirror: it tilts to show the kerb.
                val a = f.p(0.64f, -0.16f)
                drawLine(color.copy(alpha = on), a, Offset(a.x, a.y + unit * 0.1f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        CarMotion.WINDOWS, CarMotion.WINDOW_FL, CarMotion.WINDOW_FR -> {
            // The window glass slides down: a cyan band along the door shrinking towards its sill.
            val open = envelope(p, 0.3f)
            val bands = when (m) {
                CarMotion.WINDOW_FL -> listOf(Triple(0.44f, 0.62f, true))
                CarMotion.WINDOW_FR -> listOf(Triple(0.44f, 0.62f, false))
                else -> listOf(Triple(0.44f, 0.62f, true), Triple(0.27f, 0.42f, true), Triple(0.44f, 0.62f, false), Triple(0.27f, 0.42f, false))
            }
            bands.forEach { (u0, u1, driver) ->
                val v = if (driver) 0.13f else 0.87f
                val end = u1 - (u1 - u0) * 0.8f * open
                drawLine(Neon.cyan.copy(alpha = 0.25f), f.p(u0, v), f.p(u1, v), 6.dp.toPx(), StrokeCap.Round)
                drawLine(Neon.cyan, f.p(u0, v), f.p(end, v), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        CarMotion.REAR_DEFROST -> {
            val on = envelope(p)
            for (i in 0..5) {
                val v = 0.27f + i * 0.09f
                val reach = ((p * 1.6f) - i * 0.08f).coerceIn(0f, 1f)
                drawLine(warm.copy(alpha = 0.85f * on), f.p(0.19f, v), f.p(0.19f + 0.035f * reach, v), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        CarMotion.WIPER_HEAT -> {
            val on = envelope(p)
            drawLine(warm.copy(alpha = 0.35f * on), f.p(0.745f, 0.2f), f.p(0.745f, 0.8f), 10.dp.toPx(), StrokeCap.Round)
            drawLine(warm.copy(alpha = on), f.p(0.745f, 0.2f), f.p(0.745f, 0.8f), 3.dp.toPx(), StrokeCap.Round)
            // Wipers sweep once across the screen.
            val sweep = sin(p * PI).toFloat() * 60f
            for (v in listOf(0.3f, 0.62f)) {
                val base = f.p(0.745f, v)
                rotate(-sweep, base) { drawLine(Neon.text.copy(alpha = 0.8f), base, Offset(base.x - unit * 0.12f, base.y + unit * 0.2f), 2.dp.toPx(), StrokeCap.Round) }
            }
        }
        CarMotion.DEFROST -> {
            val on = envelope(p)
            for (i in 0..3) {
                val shift = (ripple(p, 2) + i * 0.25f) % 1f
                val u = 0.74f - shift * 0.12f
                drawLine(Neon.cyan.copy(alpha = 0.7f * on * (1 - shift)), f.p(u, 0.25f), f.p(u, 0.75f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        CarMotion.SEAT_HEAT_L, CarMotion.SEAT_HEAT_R, CarMotion.SEAT_VENT_L, CarMotion.SEAT_VENT_R, CarMotion.REAR_SEAT_HEAT -> {
            val vent = m == CarMotion.SEAT_VENT_L || m == CarMotion.SEAT_VENT_R
            val color = if (vent) Neon.cyan else warm
            val on = envelope(p) * (0.7f + 0.3f * flashes(p, 3))
            val seats = when (m) {
                CarMotion.SEAT_HEAT_L, CarMotion.SEAT_VENT_L -> listOf(0.53f to 0.32f)
                CarMotion.SEAT_HEAT_R, CarMotion.SEAT_VENT_R -> listOf(0.53f to 0.68f)
                else -> listOf(0.33f to 0.3f, 0.33f to 0.5f, 0.33f to 0.7f)
            }
            seats.forEach { (u, v) ->
                val c = f.p(u, v)
                val size = Size(r.width * 0.08f, unit * 0.16f)
                drawRoundRect(color.copy(alpha = 0.35f * on), Offset(c.x - size.width / 2, c.y - size.height / 2), size, CornerRadius(6.dp.toPx()))
                drawRoundRect(color.copy(alpha = on), Offset(c.x - size.width / 2, c.y - size.height / 2), size, CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                // Heat rises, cool air rises too: three short wavy strokes above the seat.
                for (k in -1..1) {
                    val x = c.x + k * size.width * 0.28f
                    val rise = ripple(p, 3)
                    drawLine(color.copy(alpha = on * (1 - rise)), Offset(x, c.y), Offset(x, c.y - size.height * (0.3f + 0.5f * rise)), 1.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        CarMotion.WHEEL_HEAT -> {
            val on = envelope(p)
            val c = f.p(0.61f, 0.32f)
            drawCircle(warm.copy(alpha = 0.3f * on), unit * 0.09f, c, style = Stroke(8.dp.toPx()))
            drawCircle(warm.copy(alpha = on), unit * 0.09f, c, style = Stroke(3.dp.toPx()))
        }
        CarMotion.RECIRC -> {
            val on = envelope(p)
            val c = f.p(0.45f, 0.5f)
            rotate(p * 360f, c) {
                drawArc(Neon.cyan.copy(alpha = on), 20f, 280f, false, Offset(c.x - unit * 0.18f, c.y - unit * 0.18f), Size(unit * 0.36f, unit * 0.36f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        CarMotion.SCREEN -> {
            val tilt = sin(p * PI).toFloat()
            val c = f.p(0.62f, 0.5f)
            val half = Size(r.width * (0.012f + 0.02f * tilt), unit * 0.14f)
            drawRoundRect(Neon.cyan.copy(alpha = 0.8f), Offset(c.x - half.width, c.y - half.height), Size(half.width * 2, half.height * 2), CornerRadius(3.dp.toPx()))
        }
        CarMotion.FRUNK, CarMotion.TRUNK -> {
            val lift = envelope(p, 0.3f)
            val front = m == CarMotion.FRUNK
            val (u0, u1) = if (front) 0.845f to 0.985f else 0.015f to 0.14f
            val dx = r.width * 0.05f * lift * if (front) 1 else -1
            translate(left = dx) {
                drawRoundRect(Neon.lime.copy(alpha = 0.22f * lift), f.p(u0, 0.14f), Size(r.width * (u1 - u0), unit * 0.72f), CornerRadius(12.dp.toPx()))
                drawRoundRect(Neon.lime.copy(alpha = lift), f.p(u0, 0.14f), Size(r.width * (u1 - u0), unit * 0.72f), CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }
        CarMotion.CHARGE_PORT -> {
            val open = envelope(p, 0.25f)
            val c = f.p(0.06f, 0.1f)
            glow(Neon.green, c, unit * 0.14f, open)
            // The flap swings open about its rear edge.
            rotate(-100f * open, Offset(c.x - unit * 0.035f, c.y)) {
                drawLine(Neon.text, Offset(c.x - unit * 0.035f, c.y), Offset(c.x + unit * 0.035f, c.y), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        CarMotion.DOOR -> {
            val open = envelope(p, 0.3f)
            val hinge = f.p(0.64f, 0.04f)
            val length = unit * 0.36f
            val angle = 38f * open
            rotate(-angle, hinge) {
                drawLine(Neon.lime.copy(alpha = 0.3f), hinge, Offset(hinge.x - length, hinge.y), 10.dp.toPx(), StrokeCap.Round)
                drawLine(Neon.lime, hinge, Offset(hinge.x - length, hinge.y), 4.dp.toPx(), StrokeCap.Round)
            }
        }
        CarMotion.GLOVE_BOX -> {
            val open = envelope(p, 0.3f)
            val c = f.p(0.66f, 0.7f)
            val size = Size(r.width * 0.05f, unit * 0.14f)
            val dx = -r.width * 0.03f * open
            drawRoundRect(Neon.lime.copy(alpha = 0.3f * open), Offset(c.x - size.width / 2 + dx, c.y - size.height / 2), size, CornerRadius(4.dp.toPx()))
            drawRoundRect(Neon.lime.copy(alpha = open), Offset(c.x - size.width / 2 + dx, c.y - size.height / 2), size, CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        CarMotion.CHILD_LOCK -> {
            val on = envelope(p)
            for (v in listOf(0.06f, 0.94f)) glow(Neon.cyan, f.p(0.34f, v), unit * 0.14f, on)
        }
        CarMotion.ACCEL, CarMotion.TRACK -> {
            val color = if (m == CarMotion.TRACK) Neon.red else Neon.lime
            val on = envelope(p)
            // Speed streaks run off the tail.
            for (i in 0..4) {
                val v = 0.15f + i * 0.175f
                val shift = (ripple(p, 3) + i * 0.13f) % 1f
                val start = f.x(-0.02f - shift * 0.15f)
                drawLine(color.copy(alpha = 0.7f * on * (1 - shift)), Offset(start, f.y(v)), Offset(start - r.width * 0.12f, f.y(v)), 2.dp.toPx(), StrokeCap.Round)
            }
            wheels(f) { c -> drawCircle(color.copy(alpha = 0.6f * on), unit * 0.07f, c, style = Stroke(2.dp.toPx())) }
        }
        CarMotion.REGEN -> {
            // Energy flows from the wheels back to the battery under the floor.
            val on = envelope(p)
            val battery = f.p(0.45f, 0.5f)
            wheelCentres(f).forEach { w ->
                val k = ripple(p, 2)
                val at = Offset(w.x + (battery.x - w.x) * k, w.y + (battery.y - w.y) * k)
                drawCircle(Neon.green.copy(alpha = on * (1 - k * 0.5f)), 4.dp.toPx(), at)
            }
            drawRoundRect(Neon.green.copy(alpha = 0.5f * on), Offset(battery.x - r.width * 0.12f, battery.y - unit * 0.12f), Size(r.width * 0.24f, unit * 0.24f), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        CarMotion.STEERING -> {
            val angle = sin(p * 2 * PI).toFloat() * 22f
            listOf(f.p(0.79f, 0.025f), f.p(0.79f, 0.975f)).forEach { c ->
                rotate(angle, c) {
                    drawRoundRect(Neon.cyan, Offset(c.x - r.width * 0.06f, c.y - unit * 0.05f), Size(r.width * 0.12f, unit * 0.10f), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
            }
        }
        CarMotion.SUSPENSION -> {
            val lift = envelope(p)
            val grow = r.width * 0.03f * lift
            drawRoundRect(Neon.cyan.copy(alpha = 0.7f * lift), Offset(r.left - grow, r.top - grow), Size(r.width + 2 * grow, r.height + 2 * grow), CornerRadius(unit * 0.3f), style = Stroke(2.dp.toPx()))
        }
        CarMotion.FOLLOW -> {
            // The car ahead drifts away and back; bars show the gap being kept.
            val gap = 0.12f + 0.1f * sin(p * PI).toFloat()
            val ahead = 1f + gap
            drawRoundRect(Neon.muted.copy(alpha = 0.6f), f.p(ahead, 0.2f), Size(r.width * 0.12f, unit * 0.6f), CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
            for (i in 1..3) {
                val u = 1f + gap * i / 4f
                drawLine(Neon.cyan, f.p(u, 0.38f), f.p(u, 0.62f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        CarMotion.TRACTION -> {
            val on = envelope(p)
            wheels(f) { c ->
                rotate(p * 720f, c) {
                    drawArc(amber.copy(alpha = on), 0f, 90f, false, Offset(c.x - unit * 0.06f, c.y - unit * 0.06f), Size(unit * 0.12f, unit * 0.12f), style = Stroke(2.dp.toPx()))
                    drawArc(amber.copy(alpha = on), 180f, 90f, false, Offset(c.x - unit * 0.06f, c.y - unit * 0.06f), Size(unit * 0.12f, unit * 0.12f), style = Stroke(2.dp.toPx()))
                }
            }
        }
        CarMotion.REAR_CAMERA -> {
            val on = envelope(p)
            val eye = f.p(0.0f, 0.5f)
            val cone = Path().apply {
                moveTo(eye.x, eye.y)
                lineTo(eye.x - r.width * 0.2f, eye.y - unit * 0.45f)
                lineTo(eye.x - r.width * 0.2f, eye.y + unit * 0.45f)
                close()
            }
            drawPath(cone, Brush.horizontalGradient(listOf(Neon.cyan.copy(alpha = 0.4f * on), Color.Transparent), eye.x, eye.x - r.width * 0.2f))
        }
        CarMotion.HORN -> {
            val nose = f.p(1.0f, 0.5f)
            for (i in 0..2) {
                val k = (ripple(p, 2) + i / 3f) % 1f
                val radius = unit * (0.1f + 0.35f * k)
                drawArc(Neon.amber.copy(alpha = 1 - k), -50f, 100f, false, Offset(nose.x - radius, nose.y - radius), Size(radius * 2, radius * 2), style = Stroke(2.dp.toPx()))
            }
        }
        CarMotion.PULSE -> {
            val k = ripple(p, 2)
            drawCircle(Neon.lime.copy(alpha = 1 - k), unit * (0.1f + 0.4f * k), f.p(m.u, m.v), style = Stroke(2.dp.toPx()))
        }
    }
}

private fun wheelCentres(f: CarFrame) = listOf(f.p(0.21f, 0.025f), f.p(0.21f, 0.975f), f.p(0.79f, 0.025f), f.p(0.79f, 0.975f))

private inline fun DrawScope.wheels(f: CarFrame, draw: DrawScope.(Offset) -> Unit) {
    wheelCentres(f).forEach { draw(it) }
}
