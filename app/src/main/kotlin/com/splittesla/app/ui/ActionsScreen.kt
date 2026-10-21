package com.splittesla.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.s3xy.CommanderAction.*

/**
 * Actions only the Commander can perform: it injects CAN frames as if the
 * driver used the stalk, scroll wheel or screen, which the official BLE
 * protocol does not expose.
 */
@Composable
fun ActionsScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val ready = ui.commander == LinkState.CONNECTED && ui.busy == null
    var showAll by rememberSaveable { mutableStateOf(false) }
    var value by rememberSaveable { mutableStateOf("") }

    fun act(action: CommanderAction) = { vm.commanderAction(action, value.toLongOrNull()) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (ui.commander != LinkState.CONNECTED) {
            Text(
                "위쪽 'S3XY' 칩을 눌러 Commander에 연결하세요.",
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("주행") {
            ButtonGrid(
                ready,
                "가속 모드" to act(DRIVE_MODE_ACCEL),
                "회생 제동" to act(DRIVE_MODE_REGEN),
                "스티어링 모드" to act(DRIVE_MODE_STEERING),
                "트랙 모드" to act(TRACK_MODE),
                "정지 모드" to act(STOPPING_MODE),
                "서스펜션" to act(SUSPENSION),
                "승차감" to act(RIDE_HANDLING),
                "트랙션 컨트롤" to act(TRACTION_CONTROL),
                "추종 거리" to act(FOLLOW_DISTANCE),
            )
        }

        Section("조명·미러") {
            ButtonGrid(
                ready,
                "비상등" to act(HAZARD_LIGHTS),
                "상향등 스트로브" to act(HIGH_BEAM_STROBE),
                "다이내믹 브레이크등" to act(DYNAMIC_BRAKE_LIGHTS),
                "땡큐 깜빡이" to act(THANK_YOU),
                "실내등" to act(INTERIOR_LIGHTS),
                "안개등" to act(ALL_FOG_LIGHTS),
                "미러 접기" to act(FOLD_MIRRORS),
                "미러 내림" to act(MIRRORS_DIP),
                "미러 감광" to act(MIRRORS_DIM),
            )
        }

        Section("실내") {
            ButtonGrid(
                ready,
                "글러브박스" to act(OPEN_GLOVE_BOX),
                "후방 카메라" to act(REAR_VIEW_CAMERA),
                "스티어링 열선" to act(STEERING_WHEEL_HEAT),
                "운전석 열선" to act(FRONT_SEAT_HEAT_LEFT),
                "조수석 열선" to act(FRONT_SEAT_HEAT_RIGHT),
                "운전석 통풍" to act(FRONT_SEAT_VENT_LEFT),
                "조수석 통풍" to act(FRONT_SEAT_VENT_RIGHT),
                "뒷유리 열선" to act(BACK_WINDOW_HEATER),
                "내기 순환" to act(RECIRC),
                "화면 기울이기" to act(SCREEN_TILT),
            )
        }

        Section("전체 동작 목록") {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(6) },
                label = { Text("값 (선택, 예: CABIN_TEMP 22)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "값을 비우면 1(토글)로 보냅니다. 위 버튼들도 이 값을 씁니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "목록 접기" else "100개 동작 모두 보기")
            }
            if (showAll) {
                CommanderAction.entries.forEach { action ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = ready, onClick = act(action)).padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(action.name, style = MaterialTheme.typography.bodyMedium)
                        Text("#${action.code}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
