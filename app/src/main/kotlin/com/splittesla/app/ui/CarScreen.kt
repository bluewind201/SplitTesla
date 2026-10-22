package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.Refresh
import com.splittesla.app.UiState
import com.splittesla.core.tesla.StateCategory
import com.tesla.generated.vcsec.Vcsec
import kotlin.math.roundToInt

/**
 * Commands sent through the car's own BLE (official vehicle-command protocol).
 * Buttons show the car's current state and flip it, and connect on first tap.
 */
@Composable
fun CarScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val confirm = rememberConfirmer()
    val idle = ui.busy == null
    val status = ui.vehicleStatus
    val charge = ui.charge
    val climate = ui.climate

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (ui.vin.length != 17) {
            Text(
                "먼저 '설정·로그' 탭에서 VIN을 저장하고 키를 등록하세요.",
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        } else if (ui.car != LinkState.CONNECTED) {
            Text(
                "버튼을 누르면 차량에 자동으로 연결합니다.",
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("잠금·도어") {
            val locked = status?.vehicleLockState?.let {
                it == Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED ||
                    it == Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        when (locked) { true -> "잠김"; false -> "열림"; null -> "상태 모름" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (locked == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(openClosures(status), style = MaterialTheme.typography.bodySmall)
                    status?.vehicleSleepStatus?.let {
                        if (it == Vcsec.VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP) {
                            Text("차량 절전 중", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (locked == true) {
                    Button(
                        onClick = confirm.guard("차량 잠금을 해제합니다.") {
                            vm.carCommand("잠금 해제", Refresh.STATUS) { it.unlock() }
                        },
                        enabled = idle,
                    ) { Text("잠금 해제") }
                } else {
                    Button(onClick = { vm.carCommand("잠금", Refresh.STATUS) { it.lock() } }, enabled = idle) { Text("잠그기") }
                }
            }
            ButtonGrid(
                idle,
                "트렁크" to confirm.guard("트렁크를 엽니다.") { vm.carCommand("트렁크 열기", Refresh.STATUS) { it.openTrunk() } },
                "프렁크" to confirm.guard("프렁크를 엽니다. 닫을 때는 손으로 눌러야 합니다.") {
                    vm.carCommand("프렁크 열기", Refresh.STATUS) { it.openFrunk() }
                },
                "깨우기" to { vm.carCommand("깨우기", Refresh.STATUS) { it.wake() } },
                "새로고침" to vm::refreshCar,
            )
        }

        Section("충전") {
            if (charge == null) {
                Text("연결하면 충전 상태가 표시됩니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${charge.batteryLevel}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(chargingLabel(charge.chargingState.typeCase.name), style = MaterialTheme.typography.titleMedium)
                }
                if (charge.hasMinutesToFullCharge() && charge.minutesToFullCharge > 0) {
                    ValueRow("완충까지", "${charge.minutesToFullCharge / 60}시간 ${charge.minutesToFullCharge % 60}분")
                }
                if (charge.hasChargerPower() && charge.chargerPower > 0) ValueRow("충전 출력", "${charge.chargerPower} kW")
            }
            val charging = charge?.chargingState?.typeCase?.name in setOf("CHARGING", "STARTING")
            val portOpen = charge?.takeIf { it.hasChargePortDoorOpen() }?.chargePortDoorOpen
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (charging) {
                    FilledTonalButton(onClick = { vm.carCommand("충전 중지", Refresh.CHARGE) { it.chargeStop() } }, enabled = idle) {
                        Text("충전 중지")
                    }
                } else {
                    FilledTonalButton(onClick = { vm.carCommand("충전 시작", Refresh.CHARGE) { it.chargeStart() } }, enabled = idle) {
                        Text("충전 시작")
                    }
                }
                if (portOpen == true) {
                    OutlinedButton(onClick = { vm.carCommand("충전구 닫기", Refresh.CHARGE) { it.chargePortClose() } }, enabled = idle) {
                        Text("충전구 닫기")
                    }
                } else {
                    OutlinedButton(onClick = { vm.carCommand("충전구 열기", Refresh.CHARGE) { it.chargePortOpen() } }, enabled = idle) {
                        Text("충전구 열기")
                    }
                }
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

        Section("공조") {
            val on = climate?.takeIf { it.hasIsClimateOn() }?.isClimateOn
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        when (on) { true -> "공조 켜짐"; false -> "공조 꺼짐"; null -> "공조" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    climate?.takeIf { it.hasInsideTempCelsius() }?.let {
                        Text("실내 %.1f°C".format(it.insideTempCelsius), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(
                    checked = on == true,
                    enabled = idle,
                    onCheckedChange = { want ->
                        vm.carCommand(if (want) "공조 켜기" else "공조 끄기", Refresh.CLIMATE) { it.climate(want) }
                    },
                )
            }
            var target by rememberSaveable { mutableFloatStateOf(21f) }
            LaunchedEffect(climate?.driverTempSetting) {
                climate?.takeIf { it.hasDriverTempSetting() }?.let { target = it.driverTempSetting }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("설정 온도", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val t = (target - 0.5f).coerceAtLeast(15f)
                        target = t
                        vm.carCommand("온도 ${t}°C", Refresh.CLIMATE) { it.setTemperature(t, t) }
                    }, enabled = idle) { Text("−") }
                    Text("%.1f°C".format(target), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = {
                        val t = (target + 0.5f).coerceAtMost(28f)
                        target = t
                        vm.carCommand("온도 ${t}°C", Refresh.CLIMATE) { it.setTemperature(t, t) }
                    }, enabled = idle) { Text("+") }
                }
            }
        }

        Section("기타") {
            ButtonGrid(
                idle,
                "라이트 깜빡" to { vm.carCommand("라이트 깜빡") { it.flashLights() } },
                "경적" to confirm.guard("경적을 울립니다.") { vm.carCommand("경적") { it.honk() } },
                "감시 모드 켜기" to { vm.carCommand("감시 모드 켜기") { it.sentry(true) } },
                "감시 모드 끄기" to { vm.carCommand("감시 모드 끄기") { it.sentry(false) } },
                "창문 환기" to { vm.carCommand("창문 환기") { it.windows(true) } },
                "창문 닫기" to { vm.carCommand("창문 닫기") { it.windows(false) } },
            )
        }

        var showRaw by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "원시 상태 조회 접기" else "고급: 원시 상태 조회") }
        if (showRaw) {
            Section("원시 상태 조회") {
                ButtonGrid(
                    idle,
                    *StateCategory.entries.map { c -> c.label() to { vm.queryState(c) } }.toTypedArray(),
                )
                if (ui.stateText.isNotEmpty()) {
                    SelectionContainer {
                        Text(ui.stateText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
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
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onApply(value) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
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
