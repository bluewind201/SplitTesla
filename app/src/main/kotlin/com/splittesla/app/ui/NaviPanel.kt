package com.splittesla.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.UiState
import com.splittesla.app.navi.NaviHub
import com.splittesla.app.navi.NaviPhase
import com.splittesla.app.navi.NaviUi
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.ChargeStop
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteGeometry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In-app guidance on the 내비 tab: search, route choice and the live
 * turn-by-turn view, all on NAVER Directions with the car's battery data.
 */
@Composable
fun NaviPanel(ui: UiState) {
    val navi by NaviHub.state.collectAsState()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { NaviHub.start() }
    val startGuidance = { permissions.launch(naviPermissions()) }

    Section("길안내 (네이버 경로)") {
        navi.busy?.let { Text("$it…", color = Neon.cyan, fontSize = 13.sp) }
        navi.message?.let { Text(it, color = Neon.amber, fontSize = 13.sp) }
        when (navi.phase) {
            NaviPhase.IDLE -> SearchView(ui, navi)
            NaviPhase.PREVIEW -> PreviewView(navi, startGuidance)
            NaviPhase.GUIDING -> GuidingView(navi)
        }
    }
}

/** What guidance asks for before it starts: location, and on Android 13+ the ongoing notification. */
fun naviPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun SearchView(ui: UiState, navi: NaviUi) {
    val prefs = NaviHub.settings
    var query by rememberSaveable { mutableStateOf("") }
    if (!prefs.hasMaps) {
        Hint("설정 탭 '길안내 API (네이버 · 충전소)'에 NAVER Cloud Maps 키를 넣으면 이 앱에서 바로 길안내합니다.")
        return
    }
    Field(query, { query = it }, "목적지 (장소 · 주소)", onDone = { NaviHub.search(query) })
    val nav = ui.nav
    val carDest = nav?.destinationLat?.let { lat -> nav.destinationLon?.let { LatLon(lat, it) } }
    val buttons = buildList<Pair<String, () -> Unit>> {
        add("검색" to { NaviHub.search(query) })
        if (carDest != null) add("차 목적지로 안내" to { NaviHub.preview(NaviPlace(nav.destination ?: "목적지", carDest)) })
    }
    ButtonGrid(navi.busy == null, *buttons.toTypedArray())
    navi.results.forEachIndexed { i, p ->
        if (i > 0) HorizontalDivider(color = Neon.stroke)
        PlaceRow(p, distanceFrom = navi.position ?: NaviHub.bestPosition()) { NaviHub.preview(p) }
    }
    val recent = remember(navi.results) { prefs.recent }
    if (navi.results.isEmpty() && recent.isNotEmpty()) {
        Text("최근 목적지", style = Caption)
        recent.forEach { p -> PlaceRow(p, null) { NaviHub.preview(p) } }
    }
}

@Composable
fun PlaceRow(p: NaviPlace, distanceFrom: LatLon?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Neon.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            distanceFrom?.let { Text(km(RouteGeometry.metres(it, p.point)), fontSize = 13.sp, color = Neon.muted) }
        }
        val sub = listOf(p.category, p.address).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = Neon.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PreviewView(navi: NaviUi, startGuidance: () -> Unit) {
    val route = navi.route ?: return
    Text(route.goal.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neon.text)
    RouteMap(navi, follow = false, Modifier.fillMaxWidth().height(220.dp))
    RouteChoices(navi)
    ButtonGrid(navi.busy == null, "안내 시작" to startGuidance, "취소" to NaviHub::clear)
}

/** The previewed route options with time, distance, toll and battery on arrival; tap to pick one. */
@Composable
fun RouteChoices(navi: NaviUi) {
    val route = navi.route ?: return
    navi.alternatives.forEach { r ->
        val active = r.option == route.option
        val soc = navi.alternativeSoc[r.option]
        Row(
            Modifier.fillMaxWidth()
                .glass(12.dp, tint = if (active) Neon.lime.copy(alpha = 0.6f) else null, edge = if (active) Neon.lime else null)
                .clickable { NaviHub.choose(r.option) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(r.option.label, fontSize = 13.sp, color = if (active) Neon.lime else Neon.muted, modifier = Modifier.width(64.dp))
            Text(NaviHub.minutes(r.durationMs), fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Neon.text)
            Text(km(r.distanceM.toDouble()), fontSize = 13.sp, color = Neon.muted, modifier = Modifier.weight(1f))
            if (r.tollFare > 0) Text("통행료 %,d원".format(r.tollFare), fontSize = 12.sp, color = Neon.muted)
            soc?.let { Text("도착 ${it.roundToInt()}%", fontSize = 13.sp, color = socColor(it)) }
        }
    }
}

@Composable
private fun GuidingView(navi: NaviUi) {
    val route = navi.route ?: return
    val p = navi.progress
    TurnCard(navi, Modifier.fillMaxWidth())
    RouteMap(navi, follow = true, Modifier.fillMaxWidth().height(260.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Big("도착", p?.let { clock(it.remainingMs) } ?: clock(route.durationMs), Neon.text)
        Big("남은 거리", km(p?.remainingM ?: route.distanceM.toDouble()), Neon.cyan)
        Big("도착 배터리", navi.socAtArrival?.let { "${it.roundToInt()}%" } ?: "—", navi.socAtArrival?.let(::socColor) ?: Neon.muted)
    }
    ValueRow("남은 주행 예상 소모", navi.kwhToGo?.let { "%.1f kWh".format(it) })
    navi.learnedWhPerKm?.let { ValueRow("이번 주행 전비", "${it.roundToInt()} Wh/km") }
    navi.nextCamera?.let { c ->
        val d = c.alongM - (p?.alongM ?: 0.0)
        ValueRow("다음 단속카메라", "${km(d)} · 제한 ${c.camera.limitKmh}", highlight = if (d < 1000) Neon.amber else null)
    }
    if (!navi.gpsOk) Text("GPS 신호 약함: 차량 속도로 위치를 추정 중", fontSize = 12.sp, color = Neon.amber)
    if (navi.waypoints.isNotEmpty()) ValueRow("경유", navi.waypoints.joinToString(" → ") { it.name })
    navi.chargeNote?.let { Text(it, fontSize = 13.sp, color = Neon.amber) }
    navi.chargeStops.forEach { ChargeRow(it) }
    ButtonGrid(
        true,
        "다시 탐색" to NaviHub::rerouteNow,
        "안내 종료" to { NaviHub.stop("길안내를 종료했습니다") },
    )
}

@Composable
private fun ChargeRow(stop: ChargeStop) {
    val s = stop.spot
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (s.tesla) Neon.lime else Neon.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val soc = stop.socOnArrival?.let { " · 도착 ${it.roundToInt()}%" }.orEmpty()
            Text(
                "${km(stop.alongM)} 앞 · 우회 ${km(stop.detourM)} · 빈 자리 ${s.available}/${s.total}" + (if (s.powerKw > 0) " · ${s.powerKw}kW" else "") + soc,
                fontSize = 12.sp, color = Neon.muted,
            )
        }
        GlowButton("경유 추가", { NaviHub.addStop(NaviPlace(s.name, s.point, s.address, if (s.tesla) "슈퍼차저" else "급속충전")) })
    }
}

@Composable
private fun Big(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = Caption)
        Text(value, fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = color)
    }
}

/**
 * The next manoeuvre in a single line for a long, low slot: symbol, distance,
 * wording (cut short if it must be), and the turn right after it.
 */
@Composable
fun TurnBanner(navi: NaviUi, modifier: Modifier = Modifier) {
    val route = navi.route ?: return
    val p = navi.progress
    val i = p?.nextGuideIndex
    val guide = i?.let { route.guides.getOrNull(it) }
    val then = i?.let { route.guides.getOrNull(it + 1) }?.takeIf { it.distanceM <= 300 }
    Row(
        modifier.glass(18.dp, tint = Neon.lime.copy(alpha = 0.35f), edge = Neon.lime.copy(alpha = 0.6f)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(guide?.maneuver?.symbol ?: "↑", fontSize = 30.sp, color = Neon.lime, fontWeight = FontWeight.Bold)
        Text(p?.nextGuideDistanceM?.let { km(it) } ?: "—", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Neon.text, maxLines = 1)
        Text(
            guide?.let { GuidanceAnnouncer.speakable(it) } ?: "${route.goal.name}까지 직진",
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Neon.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (then != null) Text("이어서 ${then.maneuver.symbol}", fontSize = 14.sp, color = Neon.cyan, maxLines = 1)
    }
}

/** The next manoeuvre, big: symbol, distance, wording, and what follows right after. */
@Composable
fun TurnCard(navi: NaviUi, modifier: Modifier = Modifier, compact: Boolean = false) {
    val route = navi.route ?: return
    val p = navi.progress
    val i = p?.nextGuideIndex
    val guide = i?.let { route.guides.getOrNull(it) }
    val then = i?.let { route.guides.getOrNull(it + 1) }?.takeIf { (it.distanceM) <= 300 }
    val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
    Row(
        modifier.glass(if (compact) 14.dp else 18.dp, tint = Neon.lime.copy(alpha = 0.35f), edge = Neon.lime.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(guide?.maneuver?.symbol ?: "↑", fontSize = if (compact) 34.sp else 46.sp, color = Neon.lime, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(
                p?.nextGuideDistanceM?.let { km(it) } ?: "—",
                fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 22.sp else 30.sp, color = Neon.text,
            )
            Text(
                guide?.let { GuidanceAnnouncer.speakable(it) } ?: "${route.goal.name}까지 직진",
                fontSize = if (compact) 13.sp else 16.sp, color = Neon.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (then != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("이어서", style = Caption)
                Text(then.maneuver.symbol, fontSize = 22.sp, color = Neon.cyan)
            }
        }
        if (compact && p != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(clock(p.remainingMs), fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Neon.text)
                Text(
                    km(p.remainingM) + (navi.socAtArrival?.let { " · ${it.roundToInt()}%" } ?: ""),
                    fontSize = 12.sp, color = navi.socAtArrival?.let(::socColor) ?: Neon.muted,
                )
            }
        }
    }
}

/**
 * The route drawn without a map tile: coloured by traffic, the driven part
 * dimmed, with the car, cameras, suggested chargers and the goal. [follow]
 * keeps the car in the lower middle with about 1.5 km ahead in view;
 * otherwise the whole route fits.
 */
@Composable
fun RouteMap(navi: NaviUi, follow: Boolean, modifier: Modifier = Modifier) {
    val route = navi.route ?: return
    val shape = RoundedCornerShape(16.dp)
    val model = remember(route) { MapModel(route) }
    // Darker glass so the route lines stand out.
    Box(modifier.background(Color.Black.copy(alpha = 0.45f), shape).glass(16.dp)) {
        Canvas(Modifier.fillMaxSize().padding(8.dp).clipToBounds()) {
            val car = navi.position
            // Following, the map turns with the road so the way ahead points up, the car low in the frame.
            val headingUp = follow && car != null
            val heading = if (headingUp) navi.bearing ?: 0f else 0f
            val (cx, cy, scale) = if (headingUp) {
                val (x, y) = model.xy(car!!)
                Triple(x, y, size.height / 1500.0)
            } else {
                val w = max(model.maxX - model.minX, 50.0)
                val h = max(model.maxY - model.minY, 50.0)
                Triple((model.minX + model.maxX) / 2, (model.minY + model.maxY) / 2, minOf(size.width / w, size.height / h) * 0.9)
            }
            val anchor = if (headingUp) Offset(size.width / 2, size.height * 0.72f) else center
            fun px(x: Double, y: Double) = Offset((anchor.x + (x - cx) * scale).toFloat(), (anchor.y + (y - cy) * scale).toFloat())
            val doneM = navi.progress?.alongM ?: 0.0
            rotate(-heading, anchor) {
                val width = if (follow) 9.dp.toPx() else 6.dp.toPx()
                model.runs.forEach { run ->
                    val path = Path()
                    for (k in run.from..run.to) {
                        val o = px(model.xs[k], model.ys[k])
                        if (k == run.from) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    val driven = model.geometry.alongOf(run.to) <= doneM
                    drawPath(
                        path,
                        if (driven) Neon.faint else congestionColor(run.congestion),
                        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                navi.cameras.forEach { c ->
                    if (c.alongM < doneM) return@forEach
                    val (x, y) = model.xy(LatLon(c.camera.latitude, c.camera.longitude))
                    drawCircle(Neon.red, 4.dp.toPx(), px(x, y))
                }
                navi.chargeStops.forEach { s ->
                    val (x, y) = model.xy(s.spot.point)
                    drawCircle(if (s.spot.tesla) Neon.lime else Neon.green, 6.dp.toPx(), px(x, y), style = Stroke(2.dp.toPx()))
                }
                route.waypoints.forEach { w ->
                    val (x, y) = model.xy(w.point)
                    drawCircle(Neon.cyan, 5.dp.toPx(), px(x, y))
                }
                val (gx, gy) = model.xy(route.goal.point)
                drawCircle(Neon.lime, 7.dp.toPx(), px(gx, gy))
                drawCircle(Neon.bg, 3.dp.toPx(), px(gx, gy))
            }
            if (car != null) {
                val (x, y) = model.xy(car)
                // The map is turned by -heading around the car, so the car itself is turned by the rest.
                val o = if (headingUp) anchor else px(x, y)
                val r = 11.dp.toPx()
                rotate((navi.bearing ?: 0f) - heading, o) {
                    val arrow = Path().apply {
                        moveTo(o.x, o.y - r)
                        lineTo(o.x + r * 0.75f, o.y + r * 0.8f)
                        lineTo(o.x, o.y + r * 0.35f)
                        lineTo(o.x - r * 0.75f, o.y + r * 0.8f)
                        close()
                    }
                    drawPath(arrow, Neon.text)
                }
            }
        }
        Text(
            if (follow) "진행 방향 ↑ · 1.5km" else "${km(route.distanceM.toDouble())} · ${NaviHub.minutes(route.durationMs)}",
            style = Caption, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        )
    }
}

/** Route points in metres east/south of the first point, with runs of equal traffic to draw. */
private class MapModel(route: NaviRoute) {
    class Run(val from: Int, val to: Int, val congestion: Int)

    private val origin = route.path.firstOrNull() ?: route.goal.point
    private val kx = 111_320.0 * cos(Math.toRadians(origin.lat))
    val geometry = RouteGeometry(route.path)
    val xs = DoubleArray(route.path.size) { (route.path[it].lon - origin.lon) * kx }
    val ys = DoubleArray(route.path.size) { (origin.lat - route.path[it].lat) * 110_540.0 }
    val minX = xs.minOrNull() ?: 0.0
    val maxX = xs.maxOrNull() ?: 0.0
    val minY = ys.minOrNull() ?: 0.0
    val maxY = ys.maxOrNull() ?: 0.0

    // Runs of about 300 m at most so the driven part greys out smoothly.
    val runs: List<Run> = buildList {
        if (route.path.size < 2) return@buildList
        val congestion = IntArray(route.path.size)
        route.sections.forEach { s ->
            for (k in s.pointIndex until minOf(s.pointIndex + s.pointCount, congestion.size)) congestion[k] = s.congestion
        }
        var start = 0
        for (k in 1 until route.path.size) {
            val split = congestion[k] != congestion[start] || geometry.alongOf(k) - geometry.alongOf(start) > 300 || k == route.path.size - 1
            if (split) {
                add(Run(start, k, congestion[start]))
                start = k
            }
        }
    }

    fun xy(p: LatLon) = (p.lon - origin.lon) * kx to (origin.lat - p.lat) * 110_540.0
}

private fun congestionColor(c: Int) = when (c) {
    1 -> Neon.green
    2 -> Neon.amber
    3 -> Neon.red
    else -> Neon.cyan
}

internal fun socColor(soc: Double) = when {
    soc < 10 -> Neon.red
    soc < 20 -> Neon.amber
    else -> Neon.green
}

internal fun km(m: Double): String = if (m >= 1000) "%.1fkm".format(m / 1000) else "${(m / 10).roundToInt() * 10}m"

internal fun clock(msFromNow: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + msFromNow))
