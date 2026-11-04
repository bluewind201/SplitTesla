package com.splittesla.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.navi.NaviHub
import com.splittesla.app.navi.NaviUi
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.app.assistant.AssistantUi
import com.splittesla.app.camera.CameraState
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.app.ui.gauge.LimitSign
import com.splittesla.app.ui.gauge.Speedometer
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.glassBackdrop
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.NeonBar
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.rememberPulse
import com.splittesla.core.camera.CameraStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The dashboard shown on its own while a navigation app shares the screen
 * (the "open navi alongside" setting). No tabs: the speedometer plus what the
 * other tabs would show while driving, the camera ahead, the car's route and
 * ETA, traffic ahead, and battery, doors and temperatures, sized for half of a
 * phone held sideways. [onMenu] brings back the full app with its tabs.
 */
@Composable
fun SplitDashboard(vm: AppViewModel, ui: UiState, onMenu: () -> Unit) {
    KeepOn()
    val camera by CameraState.status.collectAsState()
    val running by CameraState.running.collectAsState()
    val gpsSpeed by CameraState.gpsSpeedKmh.collectAsState()
    val assistant by vm.assistant.state.collectAsState()
    val shared by vm.routeSync.last.collectAsState()
    val demoTime by produceState(0f, ui.demo) {
        while (ui.demo) {
            value += 0.05f
            delay(50)
        }
    }
    val c = if (ui.demo) Cockpit.demo(demoTime) else Cockpit.from(ui, camera, gpsSpeed)

    BoxWithConstraints(Modifier.fillMaxSize().glassBackdrop().padding(horizontal = 10.dp, vertical = 6.dp)) {
        // Wide enough (a 2/3 split or a tablet): cards beside the gauge instead of under it.
        val wide = maxWidth >= 600.dp
        val cardsHeight = if (maxHeight < 360.dp) 92.dp else 112.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TopBar(vm, ui, c, onMenu)
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeArea(vm, ui, c, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CameraCard(vm, ui, c, camera, running, Modifier.weight(1f).fillMaxWidth())
                        RouteCard(ui, assistant, Modifier.weight(1f).fillMaxWidth())
                        CarCard(c, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeArea(vm, ui, c, Modifier.weight(1.15f).fillMaxHeight())
                    CameraCard(vm, ui, c, camera, running, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.height(cardsHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RouteCard(ui, assistant, Modifier.weight(1f).fillMaxHeight())
                    CarCard(c, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Ticker(assistant, shared)
        }
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

/** Link dots, gear and drive mode, the clock, and the way back to the tabs. */
@Composable
private fun TopBar(vm: AppViewModel, ui: UiState, c: Cockpit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        var searching by remember { mutableStateOf(false) }
        SearchPill({ searching = true }, label = false)
        if (searching) DestinationDialog { searching = false }
        LinkDot("S3XY", ui.commander.color()) { if (ui.commander != LinkState.CONNECTED) vm.connectCommander() }
        LinkDot("CAR", ui.car.color()) { if (ui.car != LinkState.CONNECTED) vm.connectCar() }
        Text(c.gear ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Neon.lime)
        c.driveMode?.let {
            Text(it, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (it == "SPORT" || it == "TRACK") Neon.red else Neon.muted)
        }
        Spacer(Modifier.weight(1f))
        Clock()
        GlowButton("메뉴", onMenu)
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
private fun Clock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(now)), fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Neon.text)
}

/** The user's chosen speedometer (tap to change style) with turn and blind-spot arrows. */
@Composable
private fun GaugeArea(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.clickable(interactionSource = source, indication = null) {
            vm.setGaugeStyle(GaugeStyle.entries[(ui.gaugeStyle.ordinal + 1) % GaugeStyle.entries.size])
        },
        contentAlignment = Alignment.Center,
    ) {
        Speedometer(ui.gaugeStyle, c)
        Arrow(c.turnLeft, c.blindLeft, left = true, Modifier.align(Alignment.TopStart))
        Arrow(c.turnRight, c.blindRight, left = false, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Arrow(turn: Boolean, blind: Boolean, left: Boolean, modifier: Modifier) {
    if (!turn && !blind) return
    val a = rememberPulse(380)
    val color = if (blind) Neon.red else Neon.green
    Text(if (left) "◀" else "▶", modifier.padding(4.dp), fontSize = 26.sp, color = color.copy(alpha = if (turn) a else 1f))
}

@Composable
private fun Card(modifier: Modifier, accent: Color? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .glass(14.dp, edge = accent?.copy(alpha = 0.75f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

/**
 * Camera ahead or the section being driven, big enough to read beside a map:
 * the limit sign, the distance, the zone, and a safe speed in a section.
 */
@Composable
private fun CameraCard(vm: AppViewModel, ui: UiState, c: Cockpit, camera: CameraStatus, running: Boolean, modifier: Modifier) {
    val over = c.overLimit
    val blink = if (over) rememberPulse(450) else 1f
    val ahead = camera.ahead
    val d = camera.distanceM
    val inSection = camera.sectionAverageKmh != null
    val near = ahead != null && d != null && d < 1500
    val accent = when {
        over -> Neon.red.copy(alpha = blink)
        inSection || near -> Neon.amber
        else -> null
    }
    Card(modifier, accent, onClick = if (!running) ({ vm.setCameraAlerts(true) }) else null) {
        Text(if (over) "과속 경고" else if (inSection) "구간단속" else "단속카메라", style = Caption.copy(color = if (over) Neon.red else Neon.muted))
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val limit = c.activeLimit
            if (running && limit != null) LimitSign(limit, 58.dp, flashing = over, blink = blink)
            else Text("—", fontFamily = Orbitron, fontSize = 26.sp, color = Neon.faint)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                when {
                    !running -> Text(
                        if (ui.cameraCount > 0) "알림 꺼짐\n탭해서 켜기" else "카메라 데이터 없음\n내비 탭에서 받기",
                        fontSize = 12.sp, color = Neon.muted,
                    )
                    inSection -> {
                        Text("평균 ${camera.sectionAverageKmh!!.roundToInt()}", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Neon.amber)
                        camera.sectionRemainingM?.let { Text("남은 ${distanceText(it)}", fontSize = 12.sp, color = Neon.text) }
                        camera.sectionSafeKmh?.let { Text("${it.roundToInt()} 이하 유지", fontSize = 12.sp, color = Neon.lime) }
                    }
                    near -> {
                        Text(distanceText(d!!), fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Neon.amber)
                        ahead!!.zone?.let { Text(it, fontSize = 12.sp, color = Neon.red, fontWeight = FontWeight.Bold, maxLines = 1) }
                        Text(ahead.place, fontSize = 11.sp, color = Neon.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Text("앞쪽 카메라 없음", fontSize = 12.sp, color = Neon.muted)
                }
            }
        }
        c.limitKmh?.let { Text("도로제한 $it", fontSize = 10.sp, color = Neon.faint, maxLines = 1) }
    }
}

/** The car's own route from BLE: destination, minutes and km left, delay, battery on arrival. */
@Composable
private fun RouteCard(ui: UiState, assistant: AssistantUi, modifier: Modifier) {
    val navi by NaviHub.state.collectAsState()
    if (navi.guiding) return NaviCard(navi, modifier)
    val nav = ui.nav?.takeIf { it.hasRoute }
    Card(modifier) {
        Text("경로", style = Caption)
        if (nav == null) {
            Text("테슬라 경로 없음", fontSize = 13.sp, color = Neon.muted)
            Text(if (ui.car == LinkState.CONNECTED) "차에서 목적지를 정하면 표시" else "CAR 연결 시 표시", fontSize = 11.sp, color = Neon.faint)
            return@Card
        }
        Text(nav.destination ?: "목적지", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Neon.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nav.minutesToArrival?.let { m ->
                Text("${m.roundToInt()}분", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Neon.cyan)
                Text(arrivalClock(m), fontSize = 12.sp, color = Neon.muted)
            }
            nav.kmToArrival?.let { Text("${"%.1f".format(it)}km", fontSize = 12.sp, color = Neon.muted) }
        }
        val delay = nav.trafficDelayMinutes?.takeIf { it >= 1 }?.let { "정체 +${it.roundToInt()}분" }
        val energy = nav.energyAtArrival?.let { "도착 ${it.roundToInt()}%" }
        val weather = assistant.destinationWeather?.let { "${it.icon} ${it.label}" }
        Text(
            listOfNotNull(delay, energy, weather).joinToString(" · ").ifEmpty { " " },
            fontSize = 11.sp, color = if (delay != null) Neon.amber else Neon.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** In-app guidance in the route card's place: next turn, then time, distance and battery left. */
@Composable
private fun NaviCard(navi: NaviUi, modifier: Modifier) {
    val p = navi.progress
    val guide = p?.nextGuideIndex?.let { navi.route?.guides?.getOrNull(it) }
    Card(modifier, Neon.lime) {
        Text("길안내 · ${navi.route?.goal?.name ?: ""}", style = Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!navi.gpsOk) Text(navi.positionState.label, fontSize = 12.sp, color = Neon.amber)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManeuverIcon(guide?.maneuver.takeIf { navi.gpsOk }, Neon.lime, Modifier.size(32.dp))
            Text(if (!navi.gpsOk) "—" else p?.nextGuideDistanceM?.let(::distanceText) ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Neon.text)
        }
        Text(if (!navi.gpsOk) "위치 확인 후 턴 안내" else guide?.let { GuidanceAnnouncer.speakable(it) } ?: "직진", fontSize = 12.sp, color = Neon.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                p?.let { arrivalClock(it.remainingMs / 60_000.0) },
                p?.let { distanceText(it.remainingM) },
                navi.socAtArrival?.let { "도착 ${it.roundToInt()}%" },
            ).joinToString(" · "),
            fontSize = 11.sp, color = Neon.muted, maxLines = 1,
        )
    }
}

/** Battery and range, doors and lock, inside and outside temperature. */
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
            Text(lock, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = lockColor)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(soc?.let { "$it%" } ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Neon.text)
            c.rangeKm?.let { Text("${it}km", fontSize = 12.sp, color = Neon.muted) }
            c.powerKw?.let { Text("${it.roundToInt()}kW", fontSize = 12.sp, color = if (it < 0) Neon.green else Neon.muted) }
        }
        NeonBar((soc ?: 0) / 100f, socColor)
        val temps = listOfNotNull(c.outsideTempC?.let { "외부 ${it.roundToInt()}°" }, c.insideTempC?.let { "실내 ${it.roundToInt()}°" })
        Text(temps.joinToString(" · ").ifEmpty { " " }, fontSize = 11.sp, color = Neon.muted, maxLines = 1)
    }
}

/**
 * One line at the bottom: the nearest traffic event ahead first, then the
 * assistant's last spoken note, then the destination last sent to the map.
 */
@Composable
private fun Ticker(assistant: AssistantUi, shared: String?) {
    val event = assistant.events.firstOrNull()
    val (text, color) = when {
        event != null -> "⚠ ${event.kind} ${event.road} ${event.message}".trim() to (if (event.isAccident) Neon.red else Neon.amber)
        assistant.said != null -> "🔊 ${assistant.said}" to Neon.muted
        shared != null -> shared to Neon.cyan
        else -> return
    }
    Text(
        text, Modifier.fillMaxWidth(), color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

private fun distanceText(m: Double): String = if (m >= 1000) "${"%.1f".format(m / 1000)}km" else "${m.roundToInt()}m"

private fun arrivalClock(minutes: Double): String =
    SimpleDateFormat("HH:mm 도착", Locale.KOREA).format(Date(System.currentTimeMillis() + (minutes * 60_000).toLong()))
