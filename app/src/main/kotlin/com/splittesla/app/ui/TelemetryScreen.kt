package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.UiState
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Metric
import com.splittesla.app.ui.theme.Neon
import com.splittesla.core.s3xy.VehicleSignal

/** Raw live CAN data from the Commander, grouped into panels. */
@Composable
fun TelemetryScreen(ui: UiState, modifier: Modifier) {
    val d = ui.live
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d.isEmpty) {
            Text("S3XY Commander를 연결하면 실시간 데이터가 표시됩니다. 위쪽 S3XY 표시를 누르세요.", fontSize = 13.sp, color = Neon.muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("출력", Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("BATT kW", d.batteryPowerKw.fmt(0) ?: "—")
                    Metric("PEDAL", d.accelPedalPercent?.toString() ?: "—", "%")
                }
                ValueRow("전륜 / 후륜 출력", "${d.frontPowerKw ?: "—"} / ${d.rearPowerKw ?: "—"}")
                ValueRow("전륜 / 후륜 토크", "${d.frontTorque ?: "—"} / ${d.rearTorque ?: "—"}")
                ValueRow("최대 방전 / 회생", "${d.maxDischargePowerKw.fmt(0) ?: "—"} / ${d.maxRegenPowerKw.fmt(0) ?: "—"} kW")
            }
            Section("배터리", Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("SOC", d.socPercent?.toString() ?: "—", "%", Neon.lime)
                    Metric("RANGE", d.rangeKm.fmt(0) ?: "—", "km")
                }
                ValueRow("팩 전압 / 전류", "${d.batteryVolts.fmt(1, " V") ?: "—"} / ${d.batteryAmps.fmt(1, " A") ?: "—"}")
                val cellMax = d.cellMaxVolts
                val cellMin = d.cellMinVolts
                val spread = if (cellMax != null && cellMin != null) (cellMax - cellMin) * 1000 else null
                ValueRow("셀 전압 최고 / 최저", "${cellMax.fmt(3) ?: "—"} / ${cellMin.fmt(3) ?: "—"} V")
                ValueRow("셀 편차", spread.fmt(0, " mV"))
                ValueRow("셀 온도 최고 / 최저", "${d.cellMaxTempC ?: "—"} / ${d.cellMinTempC ?: "—"} °C")
                ValueRow("잔량 / 만충 용량", "${d.nominalRemainingKwh.fmt(1) ?: "—"} / ${d.nominalFullPackNowKwh.fmt(1) ?: "—"} kWh")
                ValueRow("12V(저전압)", d.lowBusVolts.fmt(2, " V"))
            }
            Section("온도·문", Modifier.weight(1f)) {
                ValueRow("외기", d.outsideTempC.fmt(" °C"))
                ValueRow("전륜 / 후륜 스테이터", "${d.frontStatorTempC ?: "—"} / ${d.rearStatorTempC ?: "—"} °C")
                ValueRow(
                    "브레이크 FL/FR/RL/RR",
                    listOf(
                        VehicleSignal.BRAKE_TEMP_FRONT_LEFT, VehicleSignal.BRAKE_TEMP_FRONT_RIGHT,
                        VehicleSignal.BRAKE_TEMP_REAR_LEFT, VehicleSignal.BRAKE_TEMP_REAR_RIGHT,
                    ).joinToString("/") { d.long(it)?.toString() ?: "—" },
                )
                ValueRow("잠금", d.long(VehicleSignal.CAR_LOCKED)?.let { if (it == 1L) "잠김" else "열림" })
                listOf(
                    "운전석" to VehicleSignal.DOOR_FRONT_LEFT, "조수석" to VehicleSignal.DOOR_FRONT_RIGHT,
                    "뒷좌석 좌" to VehicleSignal.DOOR_REAR_LEFT, "뒷좌석 우" to VehicleSignal.DOOR_REAR_RIGHT,
                    "프렁크" to VehicleSignal.FRUNK, "트렁크" to VehicleSignal.TRUNK,
                ).forEach { (label, signal) ->
                    val latch = d.latch(signal)
                    ValueRow(label, latch?.name, if (latch != null && latch.name != "CLOSED") Neon.red else null)
                }
            }
        }
        Section("전체 신호 (${d.values.size}개, 수신 ${ui.pushCount}회)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlowButton(if (showAll) "접기" else "펼치기", { showAll = !showAll })
            }
            if (showAll) {
                d.values.entries.sortedBy { it.key.field }.forEach { (signal, value) ->
                    ValueRow("${signal.field} ${signal.name}", value.toString())
                }
            }
        }
    }
}
