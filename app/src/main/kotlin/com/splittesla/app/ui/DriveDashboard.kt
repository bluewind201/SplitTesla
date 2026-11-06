package com.splittesla.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.assistant.AssistantUi
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.NeonBar
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.glassBackdrop
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The cockpit from both links, or a fake drive while DEMO is on. */
@Composable
fun rememberCockpit(ui: UiState): Cockpit {
    val demoTime by produceState(0f, ui.demo) {
        while (ui.demo) {
            value += 0.05f
            delay(50)
        }
    }
    return if (ui.demo) Cockpit.demo(demoTime) else Cockpit.from(ui)
}

/**
 * The driving dashboard: the speedometer, the car's state and the weather
 * where the car is. Route guidance stays with the phone's navigation app, so
 * this is all the screen carries. It is shown as the dashboard tab, alone in
 * split screen, and floating over the navi while the app is in the background.
 *
 * It follows its box: cards beside the gauge when there is width, under it
 * when the box is tall (a portrait phone's 3/5 strip).
 */
@Composable
fun DriveDashboard(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier = Modifier, header: @Composable () -> Unit) {
    val assistant by vm.assistant.state.collectAsState()
    BoxWithConstraints(modifier) {
        val tall = maxHeight > maxWidth * 1.15f
        val wide = !tall && maxWidth >= 600.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header()
            when {
                tall -> {
                    FitGaugeBox(vm, ui, c, Modifier.weight(1.2f).fillMaxWidth())
                    CarCard(c, Modifier.weight(0.6f).fillMaxWidth())
                    WeatherCard(vm, ui, c, Modifier.weight(0.6f).fillMaxWidth())
                }
                wide -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitGaugeBox(vm, ui, c, Modifier.weight(1.2f).fillMaxHeight())
                    Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CarCard(c, Modifier.weight(1f).fillMaxWidth())
                        WeatherCard(vm, ui, c, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                else -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitGaugeBox(vm, ui, c, Modifier.weight(1.15f).fillMaxHeight())
                    Column(Modifier.weight(0.85f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CarCard(c, Modifier.weight(1f).fillMaxWidth())
                        WeatherCard(vm, ui, c, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            Ticker(assistant)
        }
    }
}

/** The gauge as large as fits while keeping the face's proportions. */
@Composable
fun FitGaugeBox(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = ui.gaugeStyle.aspect
        val width: Dp = min(maxWidth, maxHeight * aspect)
        GaugeBox(vm, ui, c, Modifier.size(width, width / aspect))
    }
}

/**
 * The dashboard alone, for split screen beside the navi: no tabs, and
 * [onMenu] brings the full app back.
 */
@Composable
fun SplitDashboard(vm: AppViewModel, ui: UiState, onMenu: () -> Unit) {
    KeepOn()
    val c = rememberCockpit(ui)
    DriveDashboard(vm, ui, c, Modifier.fillMaxSize().glassBackdrop().padding(horizontal = 10.dp, vertical = 6.dp)) {
        DriveTopBar(vm, ui, c) { GlowButton("메뉴", onMenu) }
    }
}

@Composable
private fun KeepOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Link dots, gear and drive mode, the clock, then [end] (a menu or close button). */
@Composable
fun DriveTopBar(vm: AppViewModel, ui: UiState, c: Cockpit, end: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LinkDot("S3XY", ui.commander.color()) { if (ui.commander != LinkState.CONNECTED) vm.connectCommander() }
        LinkDot("CAR", ui.car.color()) { if (ui.car != LinkState.CONNECTED) vm.connectCar() }
        Text(c.gear ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Neon.lime)
        c.driveMode?.let {
            Text(it, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (it == "SPORT" || it == "TRACK") Neon.red else Neon.muted)
        }
        Spacer(Modifier.weight(1f))
        StatusClock(14.sp)
        end()
    }
}

@Composable
private fun LinkDot(label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, color = Neon.muted, fontSize = 11.sp, fontFamily = Rajdhani, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Card(modifier: Modifier, accent: Color? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .glass(14.dp, edge = accent?.copy(alpha = 0.75f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) { content() }
}

/** Battery and range, doors and lock, cabin and outside temperature. */
@Composable
private fun CarCard(c: Cockpit, modifier: Modifier) {
    val soc = c.socPercent
    val socColor = when {
        soc == null -> Neon.faint
        soc < 20 -> Neon.red
        soc < 40 -> Neon.amber
        else -> Neon.lime
    }
    val open = c.open
    val (lock, lockColor) = when {
        !open.isNullOrEmpty() -> "열림 ${open.size}곳" to Neon.red
        c.locked == true -> "잠김" to Neon.green
        c.locked == false -> "잠금 해제" to Neon.amber
        else -> "잠금 —" to Neon.faint
    }
    Card(modifier, accent = if (!open.isNullOrEmpty()) Neon.red else null) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("차량", style = Caption, modifier = Modifier.weight(1f))
            Text(lock, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = lockColor)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(soc?.let { "$it%" } ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Neon.text)
            c.rangeKm?.let { Text("${it}km", fontSize = 13.sp, color = Neon.muted) }
            c.powerKw?.let { Text("${it.roundToInt()}kW", fontSize = 13.sp, color = if (it < 0) Neon.green else Neon.muted) }
        }
        NeonBar((soc ?: 0) / 100f, socColor)
        val temps = listOfNotNull(c.insideTempC?.let { "실내 ${it.roundToInt()}°" }, c.outsideTempC?.let { "외부 ${it.roundToInt()}°" })
        Text(temps.joinToString(" · ").ifEmpty { "온도 —" }, fontSize = 12.sp, color = Neon.muted, maxLines = 1)
        if (!open.isNullOrEmpty()) {
            Text(open.joinToString(" ") { it.korean }, fontSize = 12.sp, color = Neon.red, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The weather where the car is, wind and the area name. Tap to refresh. */
@Composable
private fun WeatherCard(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    val w = ui.weather
    val rainy = w != null && (w.code in 51..67 || w.code in 71..86 || w.code in 95..99)
    Card(modifier, accent = if (rainy) Neon.cyan else null, onClick = vm::refreshWeather) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("날씨", style = Caption, modifier = Modifier.weight(1f))
            ui.region?.let { Text(it, fontSize = 12.sp, color = Neon.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (w == null) {
            Text("날씨 받는 중…", fontSize = 13.sp, color = Neon.muted)
            c.outsideTempC?.let { Text("외부 ${it.roundToInt()}°", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Neon.text) }
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(w.icon, fontSize = 24.sp, color = Neon.amber)
            Text("${w.tempC.roundToInt()}°", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Neon.text)
            Text(w.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (rainy) Neon.cyan else Neon.text, maxLines = 1)
        }
        w.windKmh?.let { kmh ->
            Text("바람 ${"%.1f".format(kmh / 3.6)}m/s", fontSize = 12.sp, color = Neon.muted, maxLines = 1)
        }
    }
}

/** One line at the bottom: a traffic event ahead first, then the assistant's last note. */
@Composable
private fun Ticker(assistant: AssistantUi) {
    val event = assistant.events.firstOrNull()
    val (text, color) = when {
        event != null -> "⚠ ${event.kind} ${event.road} ${event.message}".trim() to (if (event.isAccident) Neon.red else Neon.amber)
        assistant.said != null -> "🔊 ${assistant.said}" to Neon.muted
        else -> return
    }
    Text(text, Modifier.fillMaxWidth(), color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private val Closure.korean: String
    get() = when (this) {
        Closure.FRONT_LEFT -> "운전석"
        Closure.FRONT_RIGHT -> "조수석"
        Closure.REAR_LEFT -> "뒷좌석 왼쪽"
        Closure.REAR_RIGHT -> "뒷좌석 오른쪽"
        Closure.FRUNK -> "프렁크"
        Closure.TRUNK -> "트렁크"
        Closure.CHARGE_PORT -> "충전구"
    }
