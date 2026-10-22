package com.splittesla.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.Refresh
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Metric
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.NeonBar
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.pressScale
import com.splittesla.app.ui.theme.rememberPulse
import com.splittesla.core.tesla.StateCategory
import com.tesla.generated.vcsec.Vcsec
import kotlin.math.roundToInt

/**
 * Commands sent through the car's own BLE (official vehicle-command protocol).
 * The car drawing carries the controls for the part they move; charging and
 * climate sit in the panel on the right. Everything connects on first tap.
 */
@Composable
fun CarScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val confirm = rememberConfirmer()
    val idle = ui.busy == null
    val status = ui.vehicleStatus
    val camera by CameraState.status.collectAsState()
    val cockpit = Cockpit.from(ui, camera, null)
    val locked = cockpit.locked
    val portOpen = ui.charge?.takeIf { it.hasChargePortDoorOpen() }?.chargePortDoorOpen

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.58f).fillMaxHeight()) {
            Text(
                when {
                    ui.vin.length != 17 -> "먼저 '설정'에서 VIN을 저장하고 키를 등록하세요."
                    ui.car != LinkState.CONNECTED -> "버튼을 누르면 차량에 자동으로 연결합니다."
                    status?.vehicleSleepStatus == Vcsec.VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP -> "차량 절전 중 · " + openClosures(status)
                    else -> openClosures(status)
                },
                fontSize = 12.sp,
                color = if (ui.vin.length != 17) Neon.red else Neon.muted,
            )
            CarView(
                cockpit.open,
                Modifier.weight(1f).fillMaxWidth(),
                marginX = 0.07f,
                marginY = 0.22f,
                hotspots = listOf(
                    Hotspot(0.92f, 0.5f, "프렁크", Neon.cyan, onClick = confirm.guard("프렁크를 엽니다. 닫을 때는 손으로 눌러야 합니다.") {
                        vm.carCommand("프렁크 열기", Refresh.STATUS) { it.openFrunk() }
                    }),
                    Hotspot(0.06f, 0.5f, "트렁크", Neon.cyan, onClick = confirm.guard("트렁크를 엽니다.") {
                        vm.carCommand("트렁크 열기", Refresh.STATUS) { it.openTrunk() }
                    }),
                    Hotspot(0.10f, -0.13f, if (portOpen == true) "충전구 닫기" else "충전구 열기", Neon.green, active = portOpen == true) {
                        if (portOpen == true) vm.carCommand("충전구 닫기", Refresh.CHARGE) { it.chargePortClose() }
                        else vm.carCommand("충전구 열기", Refresh.CHARGE) { it.chargePortOpen() }
                    },
                    Hotspot(0.55f, -0.13f, "창문 닫기", Neon.lime) { vm.carCommand("창문 닫기") { it.windows(false) } },
                    Hotspot(0.55f, 1.13f, "창문 환기", Neon.lime) { vm.carCommand("창문 환기") { it.windows(true) } },
                ),
                center = {
                    val unlock = confirm.guard("차량 잠금을 해제합니다.") { vm.carCommand("잠금 해제", Refresh.STATUS) { it.unlock() } }
                    LockButton(locked, idle) {
                        if (locked == true) unlock() else vm.carCommand("잠금", Refresh.STATUS) { it.lock() }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("라이트", { vm.carCommand("라이트 깜빡") { it.flashLights() } }, enabled = idle)
                GlowButton("경적", confirm.guard("경적을 울립니다.") { vm.carCommand("경적") { it.honk() } }, enabled = idle)
                GlowButton("깨우기", { vm.carCommand("깨우기", Refresh.STATUS) { it.wake() } }, enabled = idle)
                GlowButton("새로고침", vm::refreshCar, enabled = idle)
            }
        }
        Column(
            Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChargePanel(vm, ui, idle)
            ClimatePanel(vm, ui, idle)
            Section("감시 모드") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton("켜기", { vm.carCommand("감시 모드 켜기") { it.sentry(true) } }, enabled = idle, accent = Neon.red)
                    GlowButton("끄기", { vm.carCommand("감시 모드 끄기") { it.sentry(false) } }, enabled = idle)
                }
            }
            RawState(vm, ui, idle)
        }
    }
}

@Composable
private fun LockButton(locked: Boolean?, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val color = when (locked) {
        true -> Neon.green
        false -> Neon.amber
        null -> Neon.muted
    }
    val pulse = rememberPulse(1200)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .pressScale(source, 0.88f)
                .size(62.dp)
                .drawBehind {
                    drawCircle(color.copy(alpha = 0.18f * pulse), radius = size.minDimension * 0.75f)
                    drawCircle(Neon.bg.copy(alpha = 0.9f))
                }
                .border(2.dp, color, CircleShape)
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(26.dp), tint = color)
        }
        Text(
            when (locked) { true -> "잠김 · 탭해서 해제"; false -> "열림 · 탭해서 잠금"; null -> "잠금" },
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChargePanel(vm: AppViewModel, ui: UiState, idle: Boolean) {
    val charge = ui.charge
    Section("충전") {
        if (charge == null) {
            Text("연결하면 충전 상태가 표시됩니다.", fontSize = 12.sp, color = Neon.muted)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("LEVEL", "${charge.batteryLevel}", "%", Neon.lime)
                Text(chargingLabel(charge.chargingState.typeCase.name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Neon.text)
            }
            NeonBar(charge.batteryLevel / 100f, Neon.lime)
            if (charge.hasMinutesToFullCharge() && charge.minutesToFullCharge > 0) {
                ValueRow("완충까지", "${charge.minutesToFullCharge / 60}시간 ${charge.minutesToFullCharge % 60}분")
            }
            if (charge.hasChargerPower() && charge.chargerPower > 0) ValueRow("충전 출력", "${charge.chargerPower} kW")
        }
        val charging = charge?.chargingState?.typeCase?.name in setOf("CHARGING", "STARTING")
        if (charging) {
            GlowButton("충전 중지", { vm.carCommand("충전 중지", Refresh.CHARGE) { it.chargeStop() } }, enabled = idle, accent = Neon.red)
        } else {
            GlowButton("충전 시작", { vm.carCommand("충전 시작", Refresh.CHARGE) { it.chargeStart() } }, enabled = idle, filled = true)
        }
        AutoApplySlider(
            label = "충전 한도",
            current = charge?.takeIf { it.hasChargeLimitSoc() }?.chargeLimitSoc?.toFloat(),
            default = 80f,
            range = 50f..100f,
            steps = 49,
            format = { "${it.roundToInt()}%" },
            enabled = idle,
        ) { v -> vm.carCommand("충전 한도 ${v.roundToInt()}%", Refresh.CHARGE) { it.setChargeLimit(v.roundToInt()) } }
        AutoApplySlider(
            label = "충전 전류",
            current = charge?.takeIf { it.hasChargingAmps() }?.chargingAmps?.toFloat(),
            default = 16f,
            range = 5f..48f,
            steps = 42,
            format = { "${it.roundToInt()}A" },
            enabled = idle,
        ) { v -> vm.carCommand("충전 전류 ${v.roundToInt()}A", Refresh.CHARGE) { it.setChargingAmps(v.roundToInt()) } }
    }
}

@Composable
private fun ClimatePanel(vm: AppViewModel, ui: UiState, idle: Boolean) {
    val climate = ui.climate
    Section("공조") {
        val on = climate?.takeIf { it.hasIsClimateOn() }?.isClimateOn
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    when (on) { true -> "공조 켜짐"; false -> "공조 꺼짐"; null -> "공조" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on == true) Neon.cyan else Neon.text,
                )
                climate?.takeIf { it.hasInsideTempCelsius() }?.let {
                    Text("실내 %.1f°C".format(it.insideTempCelsius), fontSize = 12.sp, color = Neon.muted)
                }
            }
            Switch(
                checked = on == true,
                enabled = idle,
                onCheckedChange = { want ->
                    vm.carCommand(if (want) "공조 켜기" else "공조 끄기", Refresh.CLIMATE) { it.climate(want) }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Neon.cyan, checkedThumbColor = Color.Black),
            )
        }
        var target by rememberSaveable { mutableFloatStateOf(21f) }
        LaunchedEffect(climate?.driverTempSetting) {
            climate?.takeIf { it.hasDriverTempSetting() }?.let { target = it.driverTempSetting }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            GlowButton("−", {
                val t = (target - 0.5f).coerceAtLeast(15f)
                target = t
                vm.carCommand("온도 ${t}°C", Refresh.CLIMATE) { it.setTemperature(t, t) }
            }, enabled = idle, accent = Neon.cyan)
            Text("%.1f°".format(target), fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = Neon.text)
            GlowButton("+", {
                val t = (target + 0.5f).coerceAtMost(28f)
                target = t
                vm.carCommand("온도 ${t}°C", Refresh.CLIMATE) { it.setTemperature(t, t) }
            }, enabled = idle, accent = Neon.amber)
        }
    }
}

@Composable
private fun RawState(vm: AppViewModel, ui: UiState, idle: Boolean) {
    var showRaw by rememberSaveable { mutableStateOf(false) }
    GlowButton(if (showRaw) "원시 상태 조회 접기" else "고급: 원시 상태 조회", { showRaw = !showRaw })
    if (showRaw) {
        Section("원시 상태 조회") {
            ButtonGrid(
                idle,
                *StateCategory.entries.map { c -> c.label() to { vm.queryState(c) } }.toTypedArray(),
            )
            if (ui.stateText.isNotEmpty()) {
                SelectionContainer {
                    Text(ui.stateText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Neon.muted)
                }
            }
        }
    }
}

/**
 * A slider that starts at the car's current value and sends the new value when
 * the finger lifts, so there is no separate apply button.
 */
@Composable
private fun AutoApplySlider(
    label: String,
    current: Float?,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    enabled: Boolean,
    onApply: (Float) -> Unit,
) {
    var value by rememberSaveable { mutableFloatStateOf(current ?: default) }
    LaunchedEffect(current) { if (current != null) value = current.coerceIn(range) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = Neon.muted)
            Text(format(value), fontFamily = Orbitron, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Neon.text)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onApply(value) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Neon.lime,
                activeTrackColor = Neon.lime,
                inactiveTrackColor = Neon.stroke,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

private fun openClosures(status: Vcsec.VehicleStatus?): String {
    val c = status?.closureStatuses ?: return ""
    val closed = Vcsec.ClosureState_E.CLOSURESTATE_CLOSED
    val open = listOf(
        "운전석 앞문" to c.frontDriverDoor, "조수석 앞문" to c.frontPassengerDoor,
        "운전석 뒷문" to c.rearDriverDoor, "조수석 뒷문" to c.rearPassengerDoor,
        "트렁크" to c.rearTrunk, "프렁크" to c.frontTrunk, "충전구" to c.chargePort,
    ).filter { it.second != closed }.map { it.first }
    return if (open.isEmpty()) "문 모두 닫힘" else "열림: " + open.joinToString(", ")
}

private fun chargingLabel(case: String) = when (case) {
    "CHARGING" -> "충전 중"
    "STARTING" -> "충전 시작 중"
    "COMPLETE" -> "충전 완료"
    "STOPPED" -> "충전 멈춤"
    "DISCONNECTED" -> "케이블 없음"
    "NOPOWER" -> "전원 없음"
    "CALIBRATING" -> "보정 중"
    else -> ""
}

private fun StateCategory.label() = when (this) {
    StateCategory.CHARGE -> "충전"
    StateCategory.CLIMATE -> "공조"
    StateCategory.DRIVE -> "주행"
    StateCategory.LOCATION -> "위치"
    StateCategory.CLOSURES -> "개폐"
    StateCategory.TIRE_PRESSURE -> "타이어 공기압"
    StateCategory.SOFTWARE_UPDATE -> "소프트웨어"
    StateCategory.MEDIA -> "미디어"
}
