package com.splittesla.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.pressScale
import com.splittesla.core.s3xy.CommanderAction
import com.splittesla.core.s3xy.CommanderAction.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The S3XY actions by what they act on, one page each, with favourites first and the full list last. */
private enum class Page(val title: String, val english: String, val actions: List<CommanderAction>) {
    FAVORITES("즐겨찾기", "FAVORITES", emptyList()),
    ACCESS("문·트렁크", "DOORS & LIDS", listOf(OPEN_FRUNK, OPEN_TRUNK, CHARGE_PORT, OPEN_DOOR, OPEN_GLOVE_BOX, CHILD_UNLOCK)),
    LIGHTS(
        "조명", "LIGHTS",
        listOf(HAZARD_LIGHTS, HEADLIGHTS, HIGH_BEAM_STROBE, ALL_FOG_LIGHTS, DYNAMIC_BRAKE_LIGHTS, THANK_YOU, INTERIOR_LIGHTS, PARTY_MODE),
    ),
    MIRRORS(
        "미러·창문", "MIRRORS & GLASS",
        listOf(FOLD_MIRRORS, MIRRORS_DIP, MIRRORS_DIM, VENT_WINDOWS, FRONT_LEFT_WINDOW, FRONT_RIGHT_WINDOW, BACK_WINDOW_HEATER, WIPERS_HEAT),
    ),
    CABIN(
        "실내·공조", "CABIN",
        listOf(
            FRONT_SEAT_HEAT_LEFT, FRONT_SEAT_HEAT_RIGHT, FRONT_SEAT_VENT_LEFT, FRONT_SEAT_VENT_RIGHT, REAR_SEAT_HEAT_ALL,
            STEERING_WHEEL_HEAT, HVAC_DEFOG_DEFROST, RECIRC, SCREEN_TILT,
        ),
    ),
    DRIVE(
        "주행", "DRIVE",
        listOf(
            DRIVE_MODE_ACCEL, DRIVE_MODE_REGEN, DRIVE_MODE_STEERING, STOPPING_MODE, SUSPENSION, RIDE_HANDLING,
            FOLLOW_DISTANCE, TRACK_MODE, TRACTION_CONTROL, REAR_VIEW_CAMERA,
        ),
    ),
    ALL("전체", "ALL ACTIONS", emptyList()),
}

/**
 * Actions only the Commander can perform: it injects CAN frames as if the
 * driver used the stalk, scroll wheel or screen, which the official BLE
 * protocol does not expose.
 *
 * One page per part of the car, switched by the tabs or a swipe. Each page
 * shows the car from above beside its actions; pressing one plays out on the
 * car at once (lamps flash, lids lift, mirrors fold) while the command is
 * sent, and the tile says it is on its way. The star on a tile, or a long
 * press, pins it to the favourites page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionsScreen(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val confirm = rememberConfirmer()
    val pages = Page.entries
    val pager = rememberPagerState(initialPage = if (ui.favorites.isEmpty()) 1 else 0) { pages.size }
    val scope = rememberCoroutineScope()
    var played by remember { mutableStateOf<CommanderAction?>(null) }
    var play by remember { mutableIntStateOf(0) }

    fun run(action: CommanderAction, value: Long? = null): () -> Unit =
        confirm.guard("S3XY: ${action.label}", ask = action.needsConfirm) {
            played = action
            play++
            vm.commanderAction(action, "S3XY ${action.label}", value)
        }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { i, page ->
                PageTab(
                    if (page == Page.FAVORITES && ui.favorites.isNotEmpty()) "★ ${page.title} ${ui.favorites.size}" else page.title,
                    selected = pager.currentPage == i,
                ) { scope.launch { pager.animateScrollToPage(i) } }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp) { i ->
            val page = pages[i]
            if (page == Page.ALL) {
                AllActionsPage(ui, ::run, vm::toggleFavorite)
            } else {
                val actions = if (page == Page.FAVORITES) ui.favorites.sortedBy { it.code } else page.actions
                CategoryPage(page, actions, ui, played?.takeIf { it in actions }, play, ::run, vm::toggleFavorite)
            }
        }
    }
}

@Composable
private fun PageTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .pressScale(source, 0.94f)
            .glass(20.dp, tint = if (selected) Neon.lime.copy(alpha = 0.55f) else null, edge = if (selected) Neon.lime.copy(alpha = 0.6f) else null)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Neon.lime else Neon.text, maxLines = 1)
    }
}

/** The car acting out this page's actions beside the action tiles: side by side when wide, stacked when tall. */
@Composable
private fun CategoryPage(
    page: Page,
    actions: List<CommanderAction>,
    ui: UiState,
    played: CommanderAction?,
    play: Int,
    run: (CommanderAction, Long?) -> () -> Unit,
    onToggleFavorite: (CommanderAction) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageHeight = maxHeight * 0.4f
        if (maxWidth > maxHeight * 1.15f) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarStage(page, actions, ui, played, play, Modifier.weight(1.1f).fillMaxHeight())
                ActionGrid(page, actions, ui, run, onToggleFavorite, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CarStage(page, actions, ui, played, play, Modifier.fillMaxWidth().height(stageHeight))
                ActionGrid(page, actions, ui, run, onToggleFavorite, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CarStage(page: Page, actions: List<CommanderAction>, ui: UiState, played: CommanderAction?, play: Int, modifier: Modifier) {
    Box(modifier.glass(22.dp)) {
        S3xyCar(played?.motion, play, actions.map { it.motion }, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
            Text(page.english, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 3.sp, color = Neon.muted)
            Text(page.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neon.text)
        }
        // Bottom line: what was just sent, or how the Commander will be reached.
        val pill = Modifier.align(Alignment.BottomCenter).padding(12.dp).glass(16.dp).padding(horizontal = 14.dp, vertical = 8.dp)
        if (played != null) {
            val sending = ui.busy == "S3XY ${played.label}"
            Row(pill, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sending) CircularProgressIndicator(Modifier.size(14.dp), color = Neon.lime, strokeWidth = 2.dp)
                Text(played.label + if (sending) " · 보내는 중" else " · 보냈습니다", fontSize = 13.sp, color = Neon.text, maxLines = 1)
            }
        } else if (ui.commander != LinkState.CONNECTED) {
            Text(
                if (ui.commander == LinkState.NEEDS_PAIRING) "핸들 오른쪽 스크롤 버튼을 두 번 눌러 이 폰을 승인하세요"
                else "누르면 S3XY에 자동으로 연결한 뒤 실행합니다",
                pill,
                fontSize = 12.sp,
                color = if (ui.commander == LinkState.NEEDS_PAIRING) Neon.cyan else Neon.muted,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ActionGrid(
    page: Page,
    actions: List<CommanderAction>,
    ui: UiState,
    run: (CommanderAction, Long?) -> () -> Unit,
    onToggleFavorite: (CommanderAction) -> Unit,
    modifier: Modifier,
) {
    if (actions.isEmpty()) {
        Box(modifier.glass(22.dp).padding(20.dp), contentAlignment = Alignment.Center) {
            Text(
                "자주 쓰는 동작의 ☆을 누르면 여기에 모입니다.\n옆으로 넘겨 조명·문·미러 페이지를 보세요.",
                fontSize = 14.sp, color = Neon.muted,
            )
        }
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(156.dp),
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (page == Page.CABIN) {
            item(span = { GridItemSpan(maxLineSpan) }) { CabinTemp(ui, run) }
        }
        items(actions, key = { it.name }) { action ->
            ActionTile(
                action,
                favorite = action in ui.favorites,
                sending = ui.busy == "S3XY ${action.label}",
                enabled = ui.busy == null,
                onClick = run(action, null),
                onToggleFavorite = { onToggleFavorite(action) },
            )
        }
    }
}

/** One action: its name, what pressing does, and a star to pin it. Amber when it asks first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionTile(
    action: CommanderAction,
    favorite: Boolean,
    sending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val accent = if (action.needsConfirm) Neon.amber else Neon.lime
    Row(
        Modifier
            .fillMaxWidth()
            // One height for every tile so a two-line name doesn't stagger the rows.
            .height(84.dp)
            .pressScale(source, 0.96f)
            .glass(16.dp, tint = if (sending) accent.copy(alpha = 0.4f) else null, edge = if (sending) accent else accent.copy(alpha = 0.25f))
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleFavorite()
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                action.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled || sending) Neon.text else Neon.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    sending -> "보내는 중…"
                    action.needsConfirm -> "⚠ 확인 후 실행"
                    else -> "누르면 실행"
                },
                fontSize = 11.sp,
                color = if (action.needsConfirm) Neon.amber else Neon.muted,
            )
        }
        // The visible way to pin, alongside the long press.
        Box(Modifier.size(48.dp).clickable(onClick = onToggleFavorite), contentAlignment = Alignment.Center) {
            Text(if (favorite) "★" else "☆", fontSize = 22.sp, color = if (favorite) Neon.lime else Neon.muted)
        }
    }
}

@Composable
private fun CabinTemp(ui: UiState, run: (CommanderAction, Long?) -> () -> Unit) {
    var temp by rememberSaveable { mutableFloatStateOf(22f) }
    Row(
        Modifier.fillMaxWidth().glass(16.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("실내 온도", style = Caption)
        Text("${temp.roundToInt()}°", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Neon.text)
        Slider(
            temp, { temp = it }, Modifier.weight(1f), valueRange = 16f..28f, steps = 11,
            colors = SliderDefaults.colors(
                thumbColor = Neon.cyan, activeTrackColor = Neon.cyan, inactiveTrackColor = Neon.stroke,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
        GlowButton("설정", run(CABIN_TEMP, temp.roundToInt().toLong()), enabled = ui.busy == null, accent = Neon.cyan)
    }
}

/** Every Commander action, searchable, with an optional value for the ones that take one. */
@Composable
private fun AllActionsPage(
    ui: UiState,
    run: (CommanderAction, Long?) -> () -> Unit,
    onToggleFavorite: (CommanderAction) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val found = CommanderAction.entries.filter { q.isEmpty() || it.label.contains(q) || it.name.contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize().glass(22.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("검색 (예: 열선, 창문)") },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(6) },
                label = { Text("보낼 값 (비우면 1)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(found, key = { it.name }) { action ->
                val favorite = action in ui.favorites
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = ui.busy == null, onClick = run(action, value.toLongOrNull())),
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
                    Box(Modifier.size(48.dp).clickable { onToggleFavorite(action) }, contentAlignment = Alignment.Center) {
                        Text(if (favorite) "★" else "☆", fontSize = 22.sp, color = if (favorite) Neon.lime else Neon.muted)
                    }
                }
            }
        }
    }
}
