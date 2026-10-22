package com.splittesla.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.s3xy.CommanderAction.*
import kotlin.math.roundToInt

private val groups = listOf(
    "주행" to listOf(
        DRIVE_MODE_ACCEL, DRIVE_MODE_REGEN, DRIVE_MODE_STEERING, STOPPING_MODE, SUSPENSION, RIDE_HANDLING,
        FOLLOW_DISTANCE, TRACK_MODE, TRACTION_CONTROL,
    ),
    "조명·미러" to listOf(
        HAZARD_LIGHTS, HIGH_BEAM_STROBE, DYNAMIC_BRAKE_LIGHTS, THANK_YOU, INTERIOR_LIGHTS, ALL_FOG_LIGHTS,
        FOLD_MIRRORS, MIRRORS_DIP, MIRRORS_DIM,
    ),
    "실내" to listOf(
        OPEN_GLOVE_BOX, REAR_VIEW_CAMERA, STEERING_WHEEL_HEAT, FRONT_SEAT_HEAT_LEFT, FRONT_SEAT_HEAT_RIGHT,
        FRONT_SEAT_VENT_LEFT, FRONT_SEAT_VENT_RIGHT, BACK_WINDOW_HEATER, RECIRC, SCREEN_TILT,
    ),
)

/**
 * Actions only the Commander can perform: it injects CAN frames as if the
 * driver used the stalk, scroll wheel or screen, which the official BLE
 * protocol does not expose. Tap to run, long-press to pin to favorites.
 */
@Composable
fun ActionsScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val confirm = rememberConfirmer()
    val idle = ui.busy == null

    fun actionFor(action: CommanderAction, value: Long? = null) =
        confirm.guard("S3XY: ${action.label}", ask = action.needsConfirm) {
            vm.commanderAction(action, "S3XY ${action.label}", value)
        }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when (ui.commander) {
                    LinkState.CONNECTED -> "누르면 실행, 길게 누르면 즐겨찾기에 추가/삭제합니다."
                    LinkState.NEEDS_PAIRING -> "핸들 오른쪽 스크롤 버튼을 두 번 눌러 이 폰을 승인하세요."
                    else -> "버튼을 누르면 S3XY에 자동으로 연결합니다. 길게 누르면 즐겨찾기에 추가/삭제합니다."
                },
                fontSize = 12.sp,
                color = if (ui.commander == LinkState.NEEDS_PAIRING) Neon.cyan else Neon.muted,
            )
            Section("즐겨찾기") {
                if (ui.favorites.isEmpty()) {
                    Text("오른쪽 버튼을 길게 누르면 여기에 모입니다.", fontSize = 12.sp, color = Neon.muted)
                } else {
                    ActionChips(ui.favorites.sortedBy { it.code }, ui.favorites, idle, ::actionFor, vm::toggleFavorite)
                }
            }
            Section("실내 온도 (S3XY)") {
                var temp by rememberSaveable { mutableFloatStateOf(22f) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${temp.roundToInt()}°", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Neon.text)
                    Slider(
                        temp, { temp = it }, Modifier.weight(1f), valueRange = 16f..28f, steps = 11,
                        colors = SliderDefaults.colors(
                            thumbColor = Neon.cyan, activeTrackColor = Neon.cyan, inactiveTrackColor = Neon.stroke,
                            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                        ),
                    )
                    GlowButton("설정", actionFor(CABIN_TEMP, temp.roundToInt().toLong()), enabled = idle, accent = Neon.cyan)
                }
            }
        }
        Column(
            Modifier.weight(0.62f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groups.forEach { (title, actions) ->
                Section(title) { ActionChips(actions, ui.favorites, idle, ::actionFor, vm::toggleFavorite) }
            }
            AllActions(ui, idle, ::actionFor, vm::toggleFavorite)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionChips(
    actions: List<CommanderAction>,
    favorites: Set<CommanderAction>,
    enabled: Boolean,
    run: (CommanderAction, Long?) -> () -> Unit,
    onToggleFavorite: (CommanderAction) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            GlowButton(
                (if (action in favorites) "★ " else "") + action.label + (if (action.needsConfirm) " ⚠" else ""),
                run(action, null),
                enabled = enabled,
                accent = if (action.needsConfirm) Neon.amber else Neon.lime,
                onLongClick = { onToggleFavorite(action) },
            )
        }
    }
}

@Composable
private fun AllActions(
    ui: UiState,
    enabled: Boolean,
    run: (CommanderAction, Long?) -> () -> Unit,
    onToggleFavorite: (CommanderAction) -> Unit,
) {
    var show by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }

    GlowButton(if (show) "전체 동작 접기" else "전체 동작 100개 보기", { show = !show })
    if (!show) return

    Section("전체 동작") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("검색 (예: 열선, 창문)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.filter(Char::isDigit).take(6) },
            label = { Text("고급: 보낼 값 (비우면 1)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val q = query.trim()
        CommanderAction.entries
            .filter { q.isEmpty() || it.label.contains(q) || it.name.contains(q, ignoreCase = true) }
            .forEach { action ->
                val favorite = action in ui.favorites
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = run(action, value.toLongOrNull())),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            action.label + if (action.needsConfirm) " ⚠" else "",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Neon.text,
                        )
                        Text("${action.name} · #${action.code}", fontSize = 11.sp, color = Neon.muted)
                    }
                    TextButton(onClick = { onToggleFavorite(action) }) {
                        Text(if (favorite) "★" else "☆", fontSize = 22.sp, color = if (favorite) Neon.lime else Neon.muted)
                    }
                }
            }
    }
}
