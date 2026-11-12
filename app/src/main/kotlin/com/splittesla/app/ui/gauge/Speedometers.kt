package com.splittesla.app.ui.gauge

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.ui.Cockpit
import com.splittesla.core.camera.CameraStatus
import com.splittesla.core.camera.cameraDistanceText
import com.splittesla.core.camera.kindLabel
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.rememberPulse
import com.splittesla.app.ui.theme.Glass
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** [aspect] is width over height, so a layout can give each face the box it fills. */
enum class GaugeStyle(val title: String, val subtitle: String, val aspect: Float) {
    TRACK("TRACK", "원형 바늘", 1f),
    F1("F1", "LED 시프트", 1.55f),
    HYPER("HYPER", "세그먼트", 1.1f),
}

private const val MAX_KMH = 240f
private val needleColor = Color(0xFFFF5A1F)

/** What every speedometer face needs, with speed already smoothed. */
private class GaugeData(c: Cockpit, val speed: Float) {
    val limit = c.activeLimit
    val over = c.overLimit
    val gear = c.gear ?: "–"
    val mode = c.driveMode
    val powerKw = c.powerKw
    val soc = c.socPercent
    val hasSpeed = c.speedKmh != null
    val brake = c.brake
    val camera = c.camera
    val origin = c.originBadge
}

/**
 * The main racing speedometer. Speed is spring-animated so the needle and
 * segments sweep instead of jumping between CAN updates.
 */
@Composable
fun Speedometer(style: GaugeStyle, cockpit: Cockpit, modifier: Modifier = Modifier) {
    val speed by animateFloatAsState(
        (cockpit.speedKmh ?: 0f).coerceIn(0f, MAX_KMH),
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "speed",
    )
    val data = GaugeData(cockpit, speed)
    val blink = if (data.over) rememberPulse(450) else 0f
    when (style) {
        GaugeStyle.TRACK -> TrackGauge(data, blink, modifier)
        GaugeStyle.F1 -> F1Gauge(data, blink, modifier)
        GaugeStyle.HYPER -> HyperGauge(data, blink, modifier)
    }
}

// ── 1. TRACK: classic round racing dial with a needle ──

@Composable
private fun TrackGauge(d: GaugeData, blink: Float, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    val labels = remember(tm) { (0..MAX_KMH.toInt() step 20).associateWith { tm.measure(AnnotatedString("$it"), labelStyle) } }
    val start = 135f
    val sweep = 270f

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f * 0.96f
            val c = center
            if (d.over) glowCircle(c, r, Neon.red, blink)

            // Glass dial: light at the centre, darker towards the rim.
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.35f)), c, r), r, c)
            drawCircle(Neon.strokeHi, r, c, style = Stroke(2.dp.toPx()))
            drawCircle(Neon.stroke, r * 0.97f, c, style = Stroke(1.dp.toPx()))

            val trackR = r * 0.86f
            val trackW = 10.dp.toPx()
            arc(Neon.stroke, c, trackR, start, sweep, trackW)

            d.limit?.let { limit ->
                val a = start + sweep * (limit / MAX_KMH).coerceIn(0f, 1f)
                arc(Neon.red.copy(alpha = 0.55f), c, r * 0.94f, a, start + sweep - a, 4.dp.toPx(), StrokeCap.Butt)
            }

            for (v in 0..MAX_KMH.toInt() step 10) {
                val a = start + sweep * v / MAX_KMH
                val major = v % 20 == 0
                val outer = r * 0.92f
                val len = if (major) 14.dp.toPx() else 7.dp.toPx()
                val hot = d.limit != null && v > d.limit
                tick(c, a, outer - len, outer, if (major) 3.dp.toPx() else 1.5.dp.toPx(),
                    when { hot -> Neon.red; major -> Neon.text; else -> Neon.faint })
                if (major) {
                    labels[v]?.let { at(it, c, r * 0.68f, a, if (hot) Neon.red else Neon.muted) }
                }
            }

            val f = d.speed / MAX_KMH
            val color = if (d.over) Neon.red else Neon.lime
            if (f > 0.002f) {
                arc(color.copy(alpha = 0.10f), c, trackR, start, sweep * f, trackW * 2.6f)
                arc(color.copy(alpha = 0.22f), c, trackR, start, sweep * f, trackW * 1.7f)
                arc(color, c, trackR, start, sweep * f, trackW)
            }

            val a = start + sweep * f
            val from = r * 0.44f
            val to = r * 0.90f
            tick(c, a, from, to, 10.dp.toPx(), needleColor.copy(alpha = 0.15f))
            tick(c, a, from, to, 6.dp.toPx(), needleColor.copy(alpha = 0.35f))
            tick(c, a, from, to, 3.dp.toPx(), needleColor)

            drawCircle(Color.Black.copy(alpha = 0.55f), r * 0.40f, c)
            drawCircle(Brush.sweepGradient(listOf(Neon.strokeHi, Neon.stroke, Neon.strokeHi), c), r * 0.40f, c, style = Stroke(2.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SpeedDigits(d, 60)
            UnitLine(d, 3)
        }
        // The dial's open bottom: camera guidance (or the road's limit) above the gear.
        Column(
            Modifier.fillMaxSize().padding(bottom = 6.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GaugeCameraChip(d, blink)
            GearBadge(d.gear, d.mode)
        }
    }
}

// ── 2. F1: steering-wheel display with shift LEDs ──

@Composable
private fun F1Gauge(d: GaugeData, blink: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    val frame = if (d.over) lerp(Neon.strokeHi, Neon.red, blink) else Neon.strokeHi
    BoxWithConstraints(modifier.aspectRatio(1.55f)) {
        val big = (maxHeight.value * 0.36f).coerceIn(56f, 130f).toInt()
        Column(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (d.over) glowRoundRect(Neon.red, 28.dp.toPx(), blink)
                    // Carbon-like weave: faint diagonal lines over the dark face.
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0xCC15171C), Color(0xB30A0B0E))), cornerRadius = CornerRadius(28.dp.toPx()))
                    val step = 6.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(Color.White.copy(alpha = 0.018f), Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
                        x += step
                    }
                }
                .border(2.dp, frame, shape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            ShiftLights(d.speed / MAX_KMH, d.over, blink, Modifier.fillMaxWidth().height(26.dp))
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                F1Box("GEAR", Modifier.weight(1f)) {
                    Text(d.gear, fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = (big * 0.62f).sp, color = Neon.lime)
                }
                Column(Modifier.weight(2f), horizontalAlignment = Alignment.CenterHorizontally) {
                    SpeedDigits(d, big)
                    UnitLine(d, 4)
                }
                F1Box(if (d.camera.active) "CAMERA" else "LIMIT", Modifier.weight(1f)) {
                    val sign = if (d.camera.active) 46.dp else 58.dp
                    if (d.limit != null) LimitSign(d.limit, sign, flashing = d.over, blink = blink) else Text("—", fontFamily = Orbitron, fontSize = 28.sp, color = Neon.faint)
                    CameraLines(d.camera, d.speed, compact = true)
                }
            }
            PowerBar(d.powerKw, d.soc, d.mode)
        }
    }
}

@Composable
private fun F1Box(caption: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(caption, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 3.sp, color = Neon.muted)
        content()
    }
}

/** 15 LEDs, green → red → blue as speed climbs. All blink blue when over the limit. */
@Composable
private fun ShiftLights(fraction: Float, over: Boolean, blink: Float, modifier: Modifier) {
    Canvas(modifier) {
        val n = 15
        val gap = size.width / n
        val rad = min(gap * 0.32f, size.height * 0.42f)
        val lit = (fraction * n).roundToInt()
        for (i in 0 until n) {
            val color = when {
                over -> Neon.blue
                i < 5 -> Neon.green
                i < 10 -> Neon.red
                else -> Neon.blue
            }
            val on = if (over) blink > 0.6f else i < lit
            val p = Offset(gap * (i + 0.5f), size.height / 2f)
            drawCircle(Color.Black, rad + 2.dp.toPx(), p)
            if (on) {
                drawCircle(color.copy(alpha = 0.25f), rad * 1.8f, p)
                drawCircle(color, rad, p)
                drawCircle(Color.White.copy(alpha = 0.5f), rad * 0.35f, p + Offset(-rad * 0.25f, -rad * 0.25f))
            } else {
                drawCircle(color.copy(alpha = 0.14f), rad, p)
            }
        }
    }
}

/** Centre-zero bar: regeneration grows left in green, power right in cyan. */
@Composable
private fun PowerBar(powerKw: Float?, soc: Int?, mode: String?) {
    val p by animateFloatAsState(powerKw ?: 0f, spring(stiffness = Spring.StiffnessLow), label = "power")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("REGEN")
            Caption(powerKw?.let { "${it.roundToInt()} kW" } ?: (mode ?: ""))
            Caption("POWER")
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val mid = size.width / 2f
            drawRoundRect(Neon.stroke, cornerRadius = CornerRadius(5.dp.toPx()))
            val half = size.width / 2f
            if (p >= 0f) {
                val w = half * (p / 300f).coerceIn(0f, 1f)
                drawRoundRect(Brush.horizontalGradient(listOf(Neon.cyan.copy(alpha = 0.5f), Neon.cyan), mid, mid + w.coerceAtLeast(1f)), Offset(mid, 0f), Size(w, size.height), CornerRadius(5.dp.toPx()))
            } else {
                val w = half * (-p / 80f).coerceIn(0f, 1f)
                drawRoundRect(Brush.horizontalGradient(listOf(Neon.green, Neon.green.copy(alpha = 0.5f)), mid - w, mid), Offset(mid - w, 0f), Size(w, size.height), CornerRadius(5.dp.toPx()))
            }
            drawLine(Neon.text, Offset(mid, -2.dp.toPx()), Offset(mid, size.height + 2.dp.toPx()), 2.dp.toPx())
        }
        if (soc != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("BATT")
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(4.dp).background(Glass.track, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(soc / 100f).fillMaxHeight().background(if (soc < 20) Neon.red else Neon.lime, RoundedCornerShape(2.dp)))
                }
                Spacer(Modifier.width(8.dp))
                Caption("$soc%")
            }
        }
    }
}

// ── 3. HYPER: hypercar segmented arc ──

@Composable
private fun HyperGauge(d: GaugeData, blink: Float, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    val labels = remember(tm) { (0..MAX_KMH.toInt() step 40).associateWith { tm.measure(AnnotatedString("$it"), labelStyle) } }
    val p by animateFloatAsState(d.powerKw ?: 0f, spring(stiffness = Spring.StiffnessLow), label = "power")
    val start = 150f
    val sweep = 240f

    Box(modifier.aspectRatio(1.1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height * 1.1f) / 2f * 0.94f
            val c = Offset(size.width / 2f, size.height / 2f + r * 0.08f)
            if (d.over) glowCircle(c, r, Neon.red, blink)

            val n = 48
            val segSweep = sweep / n
            val f = d.speed / MAX_KMH
            val limitF = d.limit?.let { it / MAX_KMH }
            for (i in 0 until n) {
                val t0 = i / n.toFloat()
                val a = start + segSweep * i
                val w = (12 + 18 * t0).dp.toPx()
                val lit = t0 < f
                val beyond = limitF != null && t0 >= limitF
                val hot = when {
                    t0 < 0.55f -> lerp(Neon.lime, Neon.amber, t0 / 0.55f)
                    else -> lerp(Neon.amber, Neon.red, ((t0 - 0.55f) / 0.45f).coerceAtMost(1f))
                }
                val color = when {
                    lit && d.over -> Neon.red
                    lit -> hot
                    beyond -> Neon.red.copy(alpha = 0.16f)
                    else -> Neon.stroke
                }
                if (lit) arc(color.copy(alpha = 0.22f), c, r - w / 2f, a, segSweep - 1.6f, w + 10.dp.toPx(), StrokeCap.Butt)
                arc(color, c, r - w / 2f, a, segSweep - 1.6f, w, StrokeCap.Butt)
            }

            // Inner power ring, zero at the top: power sweeps right, regen left.
            val pr = r * 0.58f
            arc(Neon.stroke, c, pr, start, sweep, 3.dp.toPx())
            if (abs(p) > 0.5f) {
                if (p > 0) arc(Neon.cyan, c, pr, 270f, 120f * (p / 300f).coerceAtMost(1f), 5.dp.toPx())
                else {
                    val s = 120f * (-p / 80f).coerceAtMost(1f)
                    arc(Neon.green, c, pr, 270f - s, s, 5.dp.toPx())
                }
            }
            tick(c, 270f, pr - 7.dp.toPx(), pr + 7.dp.toPx(), 2.dp.toPx(), Neon.text)

            labels.forEach { (v, layout) ->
                val a = start + sweep * v / MAX_KMH
                val hot = d.limit != null && v > d.limit
                at(layout, c, r * 0.71f, a, if (hot) Neon.red else Neon.muted)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 10.dp)) {
            GearBadge(d.gear, null)
            SpeedDigits(d, 76)
            UnitLine(d, 4)
            d.mode?.let { Text(it, fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 2.sp, color = Neon.lime) }
        }
        // The arc's open bottom carries the camera guidance.
        Box(Modifier.fillMaxSize().padding(bottom = 2.dp), contentAlignment = Alignment.BottomCenter) {
            GaugeCameraChip(d, blink)
        }
    }
}

// ── Shared pieces ──

/** "KM/H", plus where the speed came from when it is not the Commander's CAN data. */
@Composable
private fun UnitLine(d: GaugeData, spacing: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("KM/H", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = spacing.sp, color = Neon.muted)
        if (d.origin.isNotEmpty()) {
            Text(
                d.origin,
                fontFamily = Rajdhani,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.Black,
                modifier = Modifier.background(Neon.cyan, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The limit sign with the camera beside it: metres to go and its kind, or a
 * section zone's running average and distance left. Only the road's limit
 * sign when no camera is near.
 */
@Composable
private fun GaugeCameraChip(d: GaugeData, blink: Float) {
    val limit = d.limit ?: return
    Row(
        Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LimitSign(limit, if (d.camera.active) 44.dp else 36.dp, flashing = d.over, blink = blink)
        if (d.camera.active) CameraLines(d.camera, d.speed, compact = false)
    }
}

/**
 * Text part of the camera guidance: "350m" in large digits and "과속카메라",
 * or for a section zone "평균 96" and "잔여 4.2km". Shared by the gauges,
 * the dashboard banner and the floating overlay.
 */
@Composable
fun CameraLines(camera: CameraStatus, speedKmh: Float?, compact: Boolean) {
    val ahead = camera.ahead
    val distance = camera.distanceM
    val align = if (compact) Alignment.CenterHorizontally else Alignment.Start
    when {
        ahead != null && distance != null -> Column(horizontalAlignment = align) {
            val near = distance <= 150
            Text(
                cameraDistanceText(distance),
                fontFamily = Orbitron,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 15.sp else 18.sp,
                color = if (near) Neon.red else Neon.amber,
                maxLines = 1,
            )
            Text(ahead.kindLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Neon.text, maxLines = 1)
        }
        camera.sectionLimitKmh != null -> Column(horizontalAlignment = align) {
            val avg = camera.sectionAverageKmh
            val over = avg != null && avg > (camera.sectionLimitKmh ?: Int.MAX_VALUE)
            Text(
                avg?.let { "평균 ${it.roundToInt()}" } ?: "구간단속",
                fontFamily = Orbitron,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 13.sp else 16.sp,
                color = if (over) Neon.red else Neon.lime,
                maxLines = 1,
            )
            val rest = listOfNotNull(
                camera.sectionRemainingM?.let { "잔여 ${cameraDistanceText(it)}" },
                camera.sectionSafeKmh?.takeIf { !compact }?.let { "≤${it.roundToInt()}" },
            ).joinToString(" · ").ifEmpty { "구간단속 중" }
            Text(rest, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Neon.text, maxLines = 1)
        }
        else -> Unit
    }
}

@Composable
private fun SpeedDigits(d: GaugeData, sizeSp: Int) {
    Text(
        if (d.hasSpeed) d.speed.roundToInt().toString() else "--",
        fontFamily = Orbitron,
        fontWeight = FontWeight.ExtraBold,
        fontSize = sizeSp.sp,
        color = if (d.over) Neon.red else Neon.text,
        style = TextStyle(lineHeight = sizeSp.sp),
    )
}

@Composable
private fun GearBadge(gear: String, mode: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("P", "R", "N", "D").forEach { g ->
            val on = g == gear
            Text(
                g,
                fontFamily = Orbitron,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (on) 18.sp else 13.sp,
                color = if (on) Neon.lime else Neon.faint,
            )
        }
        if (mode != null) {
            Spacer(Modifier.width(4.dp))
            Text(mode, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = Neon.muted)
        }
    }
}

/** Korean-style limit sign: white disc, red ring, black number. */
@Composable
fun LimitSign(limit: Int, size: Dp, flashing: Boolean = false, blink: Float = 1f) {
    val ring = if (flashing) lerp(Neon.red, Color.White, 1f - blink) else Neon.red
    Box(
        Modifier
            .size(size)
            .drawBehind { if (flashing) drawCircle(Neon.red.copy(alpha = 0.35f * blink), this.size.minDimension * 0.75f) }
            .background(Color.White, CircleShape)
            .border(size * 0.12f, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$limit", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.32f).sp, color = Color.Black)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = Neon.muted)
}

private fun DrawScope.arc(color: Color, c: Offset, r: Float, startDeg: Float, sweepDeg: Float, width: Float, cap: StrokeCap = StrokeCap.Round) {
    drawArc(color, startDeg, sweepDeg, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width, cap = cap))
}

private fun DrawScope.tick(c: Offset, deg: Float, r0: Float, r1: Float, width: Float, color: Color) {
    val rad = Math.toRadians(deg.toDouble())
    val dx = cos(rad).toFloat()
    val dy = sin(rad).toFloat()
    drawLine(color, Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * r1, c.y + dy * r1), width, StrokeCap.Round)
}

private fun DrawScope.at(layout: TextLayoutResult, c: Offset, r: Float, deg: Float, color: Color) {
    val rad = Math.toRadians(deg.toDouble())
    val x = c.x + cos(rad).toFloat() * r - layout.size.width / 2f
    val y = c.y + sin(rad).toFloat() * r - layout.size.height / 2f
    drawText(layout, color = color, topLeft = Offset(x, y))
}

private fun DrawScope.glowCircle(c: Offset, r: Float, color: Color, strength: Float) {
    for (i in 1..5) {
        drawCircle(color.copy(alpha = 0.10f * strength * (6 - i) / 5f), r + i * 5.dp.toPx(), c, style = Stroke(6.dp.toPx()))
    }
}

private fun DrawScope.glowRoundRect(color: Color, corner: Float, strength: Float) {
    for (i in 1..5) {
        val g = i * 4.dp.toPx()
        drawRoundRect(
            color.copy(alpha = 0.10f * strength * (6 - i) / 5f),
            Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(corner + g), style = Stroke(5.dp.toPx()),
        )
    }
}
