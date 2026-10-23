package com.splittesla.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.Refresh
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.app.ui.gauge.LimitSign
import com.splittesla.app.ui.gauge.Speedometer
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.NeonBar
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Panel
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.rememberPulse
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The cockpit: racing speedometer in the middle, drive mode, weather and
 * battery on the left, speed limit and the car's lock state on the right.
 */
@Composable
fun DashboardScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    KeepScreenOn()
    val camera by CameraState.status.collectAsState()
    val gpsSpeed by CameraState.gpsSpeedKmh.collectAsState()
    val demoTime by produceState(0f, ui.demo) {
        while (ui.demo) {
            value += 0.05f
            delay(50)
        }
    }
    val c = if (ui.demo) Cockpit.demo(demoTime) else Cockpit.from(ui, camera, gpsSpeed)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.26f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DriveModePanel(c, Modifier.weight(1f).fillMaxWidth())
            WeatherPanel(ui, c, vm, Modifier.weight(1f).fillMaxWidth())
            BatteryPanel(c, Modifier.weight(1f).fillMaxWidth())
        }
        Column(Modifier.weight(0.48f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val source = remember { MutableInteractionSource() }
                AnimatedContent(
                    targetState = ui.gaugeStyle,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() },
                    label = "gauge",
                    modifier = Modifier.clickable(interactionSource = source, indication = null) {
                        vm.setGaugeStyle(GaugeStyle.entries[(ui.gaugeStyle.ordinal + 1) % GaugeStyle.entries.size])
                    },
                ) { style -> Speedometer(style, c) }
                SideSignal(c.turnLeft, c.blindLeft, left = true, Modifier.align(Alignment.TopStart))
                SideSignal(c.turnRight, c.blindRight, left = false, Modifier.align(Alignment.TopEnd))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                GaugeStyle.entries.forEach { s ->
                    GlowButton(s.title, { vm.setGaugeStyle(s) }, active = s == ui.gaugeStyle)
                }
                GlowButton("DEMO", { vm.setDemo(!ui.demo) }, active = ui.demo, accent = Neon.cyan)
            }
        }
        Column(Modifier.weight(0.26f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LimitPanel(vm, ui, c, Modifier.weight(1f).fillMaxWidth())
            LockPanel(vm, ui, c, Modifier.weight(1.6f).fillMaxWidth())
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun DriveModePanel(c: Cockpit, modifier: Modifier) {
    val color = when (c.driveMode) {
        "SPORT", "TRACK" -> Neon.red
        "CHILL" -> Neon.cyan
        null -> Neon.faint
        else -> Neon.lime
    }
    Panel(modifier, padding = 12.dp) {
        Text("DRIVE MODE", style = Caption)
        Text(c.driveMode ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = color, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small("GEAR", c.gear ?: "—")
            Small("REGEN", c.regen ?: "—")
        }
    }
}

@Composable
private fun WeatherPanel(ui: UiState, c: Cockpit, vm: AppViewModel, modifier: Modifier) {
    val w = ui.weather
    Panel(modifier.clickable { vm.refreshWeather() }, padding = 12.dp) {
        Text("WEATHER", style = Caption)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w?.icon ?: "•", fontSize = 26.sp, color = Neon.amber)
            Spacer(Modifier.width(10.dp))
            val temp = w?.tempC?.toFloat() ?: c.outsideTempC
            Text(temp?.let { "${it.roundToInt()}°" } ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = Neon.text)
            Spacer(Modifier.width(8.dp))
            Text(w?.label ?: "탭해서 갱신", fontSize = 13.sp, color = Neon.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val car = c.outsideTempC?.let { "차 외기 ${it.roundToInt()}°" }
        val wind = w?.windKmh?.let { "바람 ${it.roundToInt()}km/h" }
        Text(listOfNotNull(car, wind).joinToString(" · ").ifEmpty { " " }, fontSize = 11.sp, color = Neon.muted, maxLines = 1)
    }
}

@Composable
private fun BatteryPanel(c: Cockpit, modifier: Modifier) {
    val soc = c.socPercent
    val color = when {
        soc == null -> Neon.faint
        soc < 20 -> Neon.red
        soc < 40 -> Neon.amber
        else -> Neon.lime
    }
    Panel(modifier, padding = 12.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("BATTERY", style = Caption, modifier = Modifier.weight(1f))
            Text(c.rangeKm?.let { "$it km" } ?: "", fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Neon.muted)
        }
        Text(soc?.let { "$it%" } ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Neon.text)
        NeonBar((soc ?: 0) / 100f, color)
    }
}

/**
 * Speed cameras from the public data.go.kr list (the alert service tracks
 * them). The car's own road limit from Tesla's map is shown only as a small
 * line underneath, so it can't be mistaken for a camera.
 */
@Composable
private fun LimitPanel(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    val running by CameraState.running.collectAsState()
    val camera by CameraState.status.collectAsState()
    val over = c.overLimit
    val blink = if (over) rememberPulse(450) else 1f
    val clickable = if (!running) Modifier.clickable { vm.setCameraAlerts(true) } else Modifier
    Panel(modifier.then(clickable), accent = if (over) Neon.red.copy(alpha = blink) else null, padding = 12.dp) {
        Text(if (over) "과속 경고" else "SPEED CAMERA", style = Caption.copy(color = if (over) Neon.red else Neon.muted))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ahead = camera.ahead
            val d = camera.distanceM
            val limit = camera.sectionLimitKmh ?: ahead?.limitKmh
            if (running && limit != null) LimitSign(limit, 54.dp, flashing = over, blink = blink)
            else Text("—", fontFamily = Orbitron, fontSize = 28.sp, color = Neon.faint)
            Spacer(Modifier.width(12.dp))
            Column {
                when {
                    !running -> Text(
                        if (ui.cameraCount > 0) "카메라 알림 꺼짐\n탭해서 켜기" else "카메라 데이터 없음\n내비 탭에서 받기",
                        fontSize = 11.sp, color = Neon.muted,
                    )
                    camera.sectionAverageKmh != null -> {
                        Text("구간단속 평균", fontSize = 11.sp, color = Neon.muted)
                        Text("${camera.sectionAverageKmh!!.roundToInt()}", fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Neon.amber)
                    }
                    ahead != null && d != null -> {
                        Text(ahead.zone ?: "단속카메라", fontSize = 11.sp, color = if (ahead.zone != null) Neon.red else Neon.muted)
                        Text("${d.roundToInt()}m", fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Neon.amber)
                    }
                    else -> Text("앞쪽 카메라 없음", fontSize = 11.sp, color = Neon.muted)
                }
            }
        }
        val source = if (ui.cameraCount > 0) "공공데이터 ${ui.cameraCount}개" else "공공데이터"
        val road = c.limitKmh?.let { " · 테슬라 도로제한 $it" } ?: ""
        Text(source + road, fontSize = 10.sp, color = Neon.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LockPanel(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    val open = c.open
    val (text, color) = when {
        !open.isNullOrEmpty() -> "열림 ${open.size}곳" to Neon.red
        c.locked == true -> "잠김" to Neon.green
        c.locked == false -> "잠금 해제됨" to Neon.amber
        else -> "상태 모름" to Neon.faint
    }
    Panel(modifier, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DOORS & LOCK", style = Caption)
                Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            }
            if (c.locked == false) {
                GlowButton("잠그기", { vm.carCommand("잠금", Refresh.STATUS) { it.lock() } }, enabled = ui.busy == null, filled = true)
            }
        }
        CarView(open, Modifier.weight(1f).fillMaxWidth(), headlights = false, marginX = 0.02f, marginY = 0.06f)
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

@Composable
private fun Small(caption: String, value: String) {
    Column {
        Text(caption, style = Caption.copy(fontSize = 10.sp))
        Text(value, fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Neon.text)
    }
}
