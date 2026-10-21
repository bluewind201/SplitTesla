package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.UiState
import com.splittesla.core.s3xy.VehicleSignal

private val warning = Color(0xFFD32F2F)

/** Live CAN data from the Commander: what the car's own BLE cannot stream. */
@Composable
fun DashboardScreen(ui: UiState, modifier: Modifier) {
    val d = ui.live
    var showAll by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (d.isEmpty) {
            Text("S3XY Commander를 연결하면 실시간 데이터가 표시됩니다.", Modifier.padding(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BlindSpot("◀", d.blindSpotLeft == true, d.turnSignalLeftOn == true)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.speedKmh?.toString() ?: "—", fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Text("km/h  ·  ${d.gear?.label ?: "—"}", style = MaterialTheme.typography.titleMedium)
                d.speedLimit?.let { Text("제한 $it", style = MaterialTheme.typography.bodySmall) }
            }
            BlindSpot("▶", d.blindSpotRight == true, d.turnSignalRightOn == true)
        }

        Section("출력") {
            ValueRow("배터리 출력", d.batteryPowerKw.fmt(1, " kW"))
            ValueRow("전륜 / 후륜 출력", "${d.frontPowerKw ?: "—"} / ${d.rearPowerKw ?: "—"}")
            ValueRow("전륜 / 후륜 토크", "${d.frontTorque ?: "—"} / ${d.rearTorque ?: "—"}")
            ValueRow("가속 페달", d.accelPedalPercent.fmt(" %"))
            ValueRow("최대 방전 / 회생", "${d.maxDischargePowerKw.fmt(0) ?: "—"} / ${d.maxRegenPowerKw.fmt(0) ?: "—"} kW")
        }

        Section("배터리") {
            ValueRow("SoC", d.socPercent.fmt(" %"))
            ValueRow("주행가능거리", d.rangeKm.fmt(0, " km"))
            ValueRow("팩 전압 / 전류", "${d.batteryVolts.fmt(1, " V") ?: "—"} / ${d.batteryAmps.fmt(1, " A") ?: "—"}")
            val spread = if (d.cellMaxVolts != null && d.cellMinVolts != null) (d.cellMaxVolts!! - d.cellMinVolts!!) * 1000 else null
            ValueRow("셀 전압 최고 / 최저", "${d.cellMaxVolts.fmt(3) ?: "—"} / ${d.cellMinVolts.fmt(3) ?: "—"} V")
            ValueRow("셀 편차", spread.fmt(0, " mV"))
            ValueRow("셀 온도 최고 / 최저", "${d.cellMaxTempC ?: "—"} / ${d.cellMinTempC ?: "—"} °C")
            ValueRow("잔량 / 만충 용량", "${d.nominalRemainingKwh.fmt(1) ?: "—"} / ${d.nominalFullPackNowKwh.fmt(1) ?: "—"} kWh")
            ValueRow("12V(저전압)", d.lowBusVolts.fmt(2, " V"))
        }

        Section("온도") {
            ValueRow("외기", d.outsideTempC.fmt(" °C"))
            ValueRow("전륜 / 후륜 스테이터", "${d.frontStatorTempC ?: "—"} / ${d.rearStatorTempC ?: "—"} °C")
            ValueRow(
                "브레이크 FL/FR/RL/RR",
                listOf(
                    VehicleSignal.BRAKE_TEMP_FRONT_LEFT, VehicleSignal.BRAKE_TEMP_FRONT_RIGHT,
                    VehicleSignal.BRAKE_TEMP_REAR_LEFT, VehicleSignal.BRAKE_TEMP_REAR_RIGHT,
                ).joinToString("/") { d.long(it)?.toString() ?: "—" },
            )
        }

        Section("문") {
            ValueRow("잠금", d.long(VehicleSignal.CAR_LOCKED)?.let { if (it == 1L) "잠김" else "열림" })
            listOf(
                "운전석" to VehicleSignal.DOOR_FRONT_LEFT, "조수석" to VehicleSignal.DOOR_FRONT_RIGHT,
                "뒷좌석 좌" to VehicleSignal.DOOR_REAR_LEFT, "뒷좌석 우" to VehicleSignal.DOOR_REAR_RIGHT,
                "프렁크" to VehicleSignal.FRUNK, "트렁크" to VehicleSignal.TRUNK,
            ).forEach { (label, signal) ->
                val latch = d.latch(signal)
                ValueRow(label, latch?.name, if (latch != null && latch.name != "CLOSED") warning else null)
            }
        }

        Section("전체 신호 (${d.values.size}개, 수신 ${ui.pushCount}회)") {
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "접기" else "펼치기") }
            if (showAll) {
                d.values.entries.sortedBy { it.key.field }.forEach { (signal, value) ->
                    ValueRow("${signal.field} ${signal.name}", value.toString())
                }
            }
        }
    }
}

@Composable
private fun BlindSpot(arrow: String, warn: Boolean, signal: Boolean) {
    val color = when {
        warn -> warning
        signal -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.outline
    }
    Text(arrow, fontSize = 40.sp, color = color, modifier = Modifier.padding(8.dp))
}
