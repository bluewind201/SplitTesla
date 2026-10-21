package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.core.tesla.StateCategory
import kotlin.math.roundToInt

/** Commands sent through the car's own BLE (official vehicle-command protocol). */
@Composable
fun CarScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val ready = ui.car == LinkState.CONNECTED && ui.busy == null
    var chargeLimit by rememberSaveable { mutableFloatStateOf(80f) }
    var amps by rememberSaveable { mutableFloatStateOf(16f) }
    var temp by rememberSaveable { mutableFloatStateOf(21f) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (ui.car != LinkState.CONNECTED) {
            Text(
                "위쪽 '차량 BLE' 칩을 눌러 연결하세요. 처음이면 설정 탭에서 VIN 입력과 키 등록을 먼저 하세요.",
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("차량 상태 (VCSEC)") {
            val s = ui.vehicleStatus
            ValueRow("잠금", s?.vehicleLockState?.name?.removePrefix("VEHICLELOCKSTATE_"))
            ValueRow("절전", s?.vehicleSleepStatus?.name?.removePrefix("VEHICLE_SLEEP_STATUS_"))
            ValueRow("탑승자", s?.userPresence?.name?.removePrefix("VEHICLE_USER_PRESENCE_"))
            s?.closureStatuses?.let { c ->
                ValueRow("운전석 앞문", c.frontDriverDoor.name.removePrefix("CLOSURESTATE_"))
                ValueRow("조수석 앞문", c.frontPassengerDoor.name.removePrefix("CLOSURESTATE_"))
                ValueRow("운전석 뒷문", c.rearDriverDoor.name.removePrefix("CLOSURESTATE_"))
                ValueRow("조수석 뒷문", c.rearPassengerDoor.name.removePrefix("CLOSURESTATE_"))
                ValueRow("트렁크", c.rearTrunk.name.removePrefix("CLOSURESTATE_"))
                ValueRow("프렁크", c.frontTrunk.name.removePrefix("CLOSURESTATE_"))
                ValueRow("충전구", c.chargePort.name.removePrefix("CLOSURESTATE_"))
            }
            ButtonGrid(ready, "새로고침" to vm::refreshStatus)
        }

        Section("잠금·도어") {
            ButtonGrid(
                ready,
                "깨우기" to { vm.carCommand("깨우기") { it.wake() } },
                "잠금" to { vm.carCommand("잠금") { it.lock() } },
                "잠금 해제" to { vm.carCommand("잠금 해제") { it.unlock() } },
                "트렁크" to { vm.carCommand("트렁크") { it.openTrunk() } },
                "프렁크" to { vm.carCommand("프렁크") { it.openFrunk() } },
            )
        }

        Section("충전") {
            ButtonGrid(
                ready,
                "충전 시작" to { vm.carCommand("충전 시작") { it.chargeStart() } },
                "충전 중지" to { vm.carCommand("충전 중지") { it.chargeStop() } },
                "충전구 열기" to { vm.carCommand("충전구 열기") { it.chargePortOpen() } },
                "충전구 닫기" to { vm.carCommand("충전구 닫기") { it.chargePortClose() } },
            )
            SliderRow("충전 한도", "${chargeLimit.roundToInt()}%", chargeLimit, 50f..100f, 49, { chargeLimit = it }, ready) {
                vm.carCommand("충전 한도 ${chargeLimit.roundToInt()}%") { it.setChargeLimit(chargeLimit.roundToInt()) }
            }
            SliderRow("충전 전류", "${amps.roundToInt()}A", amps, 5f..48f, 42, { amps = it }, ready) {
                vm.carCommand("충전 전류 ${amps.roundToInt()}A") { it.setChargingAmps(amps.roundToInt()) }
            }
        }

        Section("공조") {
            ButtonGrid(
                ready,
                "공조 켜기" to { vm.carCommand("공조 켜기") { it.climate(true) } },
                "공조 끄기" to { vm.carCommand("공조 끄기") { it.climate(false) } },
            )
            SliderRow("온도", "%.1f°C".format(temp), temp, 15f..28f, 25, { temp = (it * 2).roundToInt() / 2f }, ready) {
                vm.carCommand("온도 ${temp}°C") { it.setTemperature(temp, temp) }
            }
        }

        Section("기타") {
            ButtonGrid(
                ready,
                "라이트 깜빡" to { vm.carCommand("라이트 깜빡") { it.flashLights() } },
                "경적" to { vm.carCommand("경적") { it.honk() } },
                "감시 모드 켜기" to { vm.carCommand("감시 모드 켜기") { it.sentry(true) } },
                "감시 모드 끄기" to { vm.carCommand("감시 모드 끄기") { it.sentry(false) } },
                "창문 환기" to { vm.carCommand("창문 환기") { it.windows(true) } },
                "창문 닫기" to { vm.carCommand("창문 닫기") { it.windows(false) } },
            )
        }

        Section("상태 조회 (인포테인먼트)") {
            ButtonGrid(
                ready,
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

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label $valueText", Modifier.weight(0.35f))
        Slider(value, onChange, Modifier.weight(0.45f), valueRange = range, steps = steps)
        Button(onClick = onApply, enabled = enabled) { Text("적용") }
    }
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
