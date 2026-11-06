package com.splittesla.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.Refresh
import com.splittesla.app.UiState
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.app.ui.gauge.LimitSign
import com.splittesla.app.ui.gauge.Speedometer
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.Glass
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.rememberPulse
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.unit.min
import kotlin.math.roundToInt

/**
 * Weather, wind, battery, area and locks as one line of small icons, so the
 * gauge can have the room. Tapping the weather refreshes it; tapping an open
 * padlock locks the car.
 */
@Composable
fun CockpitStatus(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier = Modifier) {
    val w = ui.weather
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.clickable { vm.refreshWeather() }, verticalAlignment = Alignment.CenterVertically) {
            Text(w?.icon ?: "•", fontSize = 15.sp, color = Neon.amber)
            val temp = w?.tempC?.toFloat() ?: c.outsideTempC
            StatusText(temp?.let { " ${it.roundToInt()}°" } ?: " —")
        }
        w?.windKmh?.let { kmh ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                WindArrow(w.windFromDeg)
                StatusText(" %.1fm/s".format(kmh / 3.6))
            }
        }
        c.socPercent?.let { soc ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BatteryGlyph(soc)
                StatusText(" $soc%")
            }
        }
        ui.region?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, null, Modifier.size(14.dp), Neon.muted)
                Text(it, fontSize = 12.sp, color = Neon.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LockGlyph(c) { vm.carCommand("잠금", Refresh.STATUS) { it.lock() } }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Neon.text, maxLines = 1)
}

/** Points where the wind is blowing to. */
@Composable
private fun WindArrow(fromDeg: Double?) {
    Canvas(Modifier.size(14.dp)) {
        rotate(((fromDeg ?: 0.0) + 180).toFloat()) {
            val w = size.width
            val h = size.height
            drawLine(Neon.cyan, Offset(w / 2, h * 0.9f), Offset(w / 2, h * 0.15f), 2.dp.toPx(), StrokeCap.Round)
            val head = Path().apply {
                moveTo(w / 2, h * 0.05f)
                lineTo(w * 0.85f, h * 0.45f)
                lineTo(w * 0.15f, h * 0.45f)
                close()
            }
            drawPath(head, Neon.cyan)
        }
    }
}

@Composable
private fun BatteryGlyph(soc: Int) {
    val color = when {
        soc < 20 -> Neon.red
        soc < 40 -> Neon.amber
        else -> Neon.green
    }
    Canvas(Modifier.size(width = 20.dp, height = 12.dp)) {
        val s = 1.6.dp.toPx()
        val body = Size(size.width - 3.dp.toPx(), size.height)
        drawRoundRect(color, Offset.Zero, body, CornerRadius(3.dp.toPx()), style = Stroke(s))
        val inner = Size((body.width - 4 * s) * soc / 100f, body.height - 4 * s)
        drawRoundRect(color, Offset(2 * s, 2 * s), inner, CornerRadius(1.dp.toPx()))
        drawLine(color, Offset(size.width - s, size.height * 0.35f), Offset(size.width - s, size.height * 0.65f), s * 1.4f, StrokeCap.Round)
    }
}

/** Padlock: green locked, amber unlocked (tap to lock), red with a count when something is open. */
@Composable
private fun LockGlyph(c: Cockpit, onLock: () -> Unit) {
    val open = c.open
    val color = when {
        !open.isNullOrEmpty() -> Neon.red
        c.locked == true -> Neon.green
        c.locked == false -> Neon.amber
        else -> return
    }
    val closedShackle = c.locked == true && open.isNullOrEmpty()
    Row(
        Modifier.then(if (c.locked == false) Modifier.clickable(onClick = onLock) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 14.dp, height = 16.dp)) {
            val s = 1.8.dp.toPx()
            val top = size.height * 0.45f
            drawRoundRect(color, Offset(0f, top), Size(size.width, size.height - top), CornerRadius(2.dp.toPx()))
            val r = size.width * 0.3f
            val cx = if (closedShackle) size.width / 2 else size.width * 0.72f
            val shackle = Path().apply {
                moveTo(cx - r, top)
                lineTo(cx - r, r + s)
                arcTo(androidx.compose.ui.geometry.Rect(cx - r, s, cx + r, 2 * r + s), 180f, 180f, false)
                lineTo(cx + r, if (closedShackle) top else r * 1.6f)
            }
            drawPath(shackle, color, style = Stroke(s, cap = StrokeCap.Round))
        }
        if (!open.isNullOrEmpty()) Text(" 열림 ${open.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Neon.red)
    }
}

/** HH:mm, ticking. */
@Composable
fun StatusClock(fontSize: TextUnit = 16.sp) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(now)), fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = fontSize, color = Neon.text)
}

/**
 * The dashboard's only chrome: the menu button, the status icons, link dots
 * and the clock.
 */
@Composable
fun CockpitTopLine(vm: AppViewModel, ui: UiState, c: Cockpit, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundIcon({ Icon(Icons.Filled.Menu, "메뉴", Modifier.size(18.dp), Neon.text) }, onMenu)
        CockpitStatus(vm, ui, c, Modifier.weight(1f))
        if (ui.busy != null) CircularProgressIndicator(Modifier.size(16.dp), color = Neon.lime, strokeWidth = 2.dp)
        StatusDot("S3XY", ui.commander) { if (ui.commander != LinkState.CONNECTED) vm.connectCommander() }
        StatusDot("CAR", ui.car) { if (ui.car != LinkState.CONNECTED) vm.connectCar() }
        StatusClock(14.sp)
    }
}

@Composable
private fun RoundIcon(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).glass(17.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { icon() }
}

/** A link as a coloured dot and its name, for tight top bars. */
@Composable
fun StatusDot(label: String, state: LinkState, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(state.color(), CircleShape))
        Text(" $label", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Neon.muted)
    }
}

/**
 * The speedometer in a box shaped for the chosen face. A tap changes the face.
 * The faces are drawn for about 320 dp;
 * a bigger or smaller box scales everything in them (digits, ticks, badges)
 * together, so a large screen gets a large gauge rather than small type.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GaugeBox(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val base = LocalDensity.current
        val scale = (min(maxWidth, maxHeight) / 320.dp).coerceIn(0.75f, 2f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            AnimatedContent(
                targetState = ui.gaugeStyle,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() },
                label = "gauge",
                modifier = Modifier.fillMaxSize().combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClick = { vm.setGaugeStyle(GaugeStyle.entries[(ui.gaugeStyle.ordinal + 1) % GaugeStyle.entries.size]) },
                ),
                contentAlignment = Alignment.Center,
            ) { style -> Speedometer(style, c, Modifier.fillMaxSize()) }
            SideSignal(c.turnLeft, c.blindLeft, left = true, Modifier.align(Alignment.TopStart))
            SideSignal(c.turnRight, c.blindRight, left = false, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Turn signal arrow; turns red while the blind spot on that side is occupied. */
@Composable
private fun SideSignal(turn: Boolean, blind: Boolean, left: Boolean, modifier: Modifier) {
    if (!turn && !blind) return
    val a = rememberPulse(380)
    val color: Color = if (blind) Neon.red else Neon.green
    Text(
        if (left) "◀" else "▶",
        modifier.padding(8.dp),
        fontSize = 34.sp,
        color = color.copy(alpha = if (turn) a else 1f),
    )
}
