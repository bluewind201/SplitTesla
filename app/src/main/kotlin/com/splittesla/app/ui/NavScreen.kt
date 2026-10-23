package com.splittesla.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.assistant.NaviApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Route, position and nearby Superchargers read from the car's own BLE, with
 * speed and the speed limit from the Commander's live CAN stream.
 */
@Composable
fun NavScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val context = LocalContext.current
    val ready = ui.vin.length == 17 && ui.busy == null
    val nav = ui.nav
    val clock = SimpleDateFormat("HH:mm", Locale.KOREA)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.car != LinkState.CONNECTED) {
                Text(
                    "경로 정보는 차량 BLE로 읽습니다. 새로고침을 누르면 자동으로 연결합니다.",
                    Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            CameraSection(vm, ui)

            Section("주행") {
                val speed = ui.live.speedKmh ?: nav?.speedKmh?.toLong()
                val limit = ui.live.speedLimit
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BigValue("속도", speed?.let { "$it" } ?: "—", "km/h")
                    BigValue(
                        "도로 제한속도 (테슬라)",
                        limit?.takeIf { it > 0 }?.toString() ?: "—",
                        "km/h",
                        warn = speed != null && limit != null && limit > 0 && speed > limit,
                    )
                }
                Text(
                    if (ui.commander != LinkState.CONNECTED) "도로 제한속도는 S3XY 연결 시 테슬라 지도 값으로 표시됩니다."
                    else "도로 제한속도는 테슬라 지도 값입니다. 단속카메라는 위의 공공데이터로 따로 알립니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("폰 내비 길안내") {
                val lat = nav?.destinationLat
                val lon = nav?.destinationLon
                val assistant by vm.assistant.state.collectAsState()
                val shared by vm.routeSync.last.collectAsState()
                Text(
                    if (assistant.autoShareRoute) "자동 공유 켜짐: 차에서 목적지가 바뀌면 ${assistant.naviApp.label}(으)로 바로 보냅니다."
                    else "자동 공유 꺼짐 (설정 탭에서 켜기)",
                    fontSize = 12.sp,
                    color = if (assistant.autoShareRoute) Neon.green else Neon.muted,
                )
                shared?.let { Text("최근 전송: $it", fontSize = 12.sp, color = Neon.cyan) }
                if (lat != null && lon != null) {
                    val name = nav?.destination ?: "목적지"
                    Text("차에 설정된 목적지($name)로 폰 내비를 바로 시작합니다.", fontSize = 12.sp, color = Neon.muted)
                    ButtonGrid(
                        true,
                        *NaviApp.entries.map { a -> a.label to { NaviLaunch.route(context, a, lat, lon, name) } }.toTypedArray(),
                        "다른 지도 앱" to { openMap(context, lat, lon, name) },
                    )
                } else {
                    Text(
                        "차에 목적지를 설정하면 그 목적지로 바로 길안내합니다. 지금은 지도 앱만 엽니다.",
                        fontSize = 12.sp,
                        color = Neon.muted,
                    )
                    ButtonGrid(true, *NaviApp.entries.map { a -> "${a.label} 열기" to { NaviLaunch.open(context, a) } }.toTypedArray())
                }
            }
            Section("경로") {
                when {
                    nav == null -> Text("아직 읽지 않았습니다. 새로고침을 누르세요.", style = MaterialTheme.typography.bodyMedium)
                    !nav.hasRoute -> Text("차량에 설정된 경로가 없습니다.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(nav.destination ?: "목적지", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        ValueRow(
                            "남은 시간",
                            nav.minutesToArrival?.let { m ->
                                val eta = clock.format(Date(System.currentTimeMillis() + (m * 60_000).toLong()))
                                "${formatMinutes(m)} (도착 $eta)"
                            },
                        )
                        ValueRow("남은 거리", nav.kmToArrival.fmt(1, " km"))
                        ValueRow(
                            "교통 지연",
                            nav.trafficDelayMinutes?.let { if (it < 1) "없음" else "+${formatMinutes(it)}" },
                            highlight = nav.trafficDelayMinutes?.takeIf { it >= 5 }?.let { MaterialTheme.colorScheme.error },
                        )
                        ValueRow("도착 시 배터리", nav.energyAtArrival.fmt(0, "%"))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    ButtonGrid(ready, "새로고침" to vm::refreshNavigation)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("10초 자동 갱신", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = ui.navAutoRefresh, onCheckedChange = vm::setNavAutoRefresh, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                ui.navUpdatedAt?.let {
                    Text("마지막 갱신 ${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(it))}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("현재 위치") {
                ValueRow("위치", nav?.locationName)
                ValueRow(
                    "좌표",
                    if (nav?.latitude != null && nav.longitude != null) "%.5f, %.5f".format(nav.latitude, nav.longitude) else null,
                )
                ValueRow("방향", nav?.heading?.let { "$it° ${compass(it)}" })
                if (nav?.latitude != null && nav.longitude != null) {
                    ButtonGrid(true, "폰 지도에서 열기" to { openMap(context, nav.latitude!!, nav.longitude!!, "내 차") })
                }
            }

            Section("주변 슈퍼차저") {
                ButtonGrid(ready, "찾기" to vm::findChargingSites)
                if (ui.chargingSites.isEmpty()) {
                    Text("차량이 알려주는 주변 슈퍼차저와 빈 자리를 보여줍니다.", style = MaterialTheme.typography.bodySmall)
                }
                ui.chargingSites.forEachIndexed { i, site ->
                    if (i > 0) HorizontalDivider()
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { openMap(context, site.latitude, site.longitude, site.name) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(site.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("%.1f km".format(site.distanceKm), style = MaterialTheme.typography.bodyMedium)
                        }
                        val stalls = if (site.closed) "운영 중지" else "빈 자리 ${site.availableStalls}/${site.totalStalls}"
                        val power = if (site.maxPowerKw > 0) " · 최대 ${site.maxPowerKw}kW" else ""
                        val range = if (site.withinRange) "" else " · 주행가능거리 밖"
                        Text(
                            stalls + power + range,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (site.closed || site.availableStalls == 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (site.address.isNotBlank()) Text(site.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                "차량에 목적지를 보내는 기능은 테슬라 BLE에 없고 원격 API(Fleet API) 전용이라 넣지 않았습니다. " +
                    "목적지는 차량 화면이나 음성 명령으로 설정하면 이 화면에 표시됩니다.",
                Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BigValue(label: String, value: String, unit: String, warn: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            fontFamily = Orbitron,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 40.sp,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(unit, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatMinutes(m: Double): String {
    val total = m.toInt()
    return if (total >= 60) "${total / 60}시간 ${total % 60}분" else "${total}분"
}

private fun compass(deg: Int): String =
    listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")[((deg % 360 + 22) / 45) % 8]

/** Hands a point to whichever map app the user picks (Kakao, Naver, T map, Google). */
private fun openMap(context: Context, lat: Double, lon: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "지도 앱 선택"))
    } catch (_: ActivityNotFoundException) {
        // No map app installed; nothing to open.
    }
}
