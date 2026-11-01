package com.splittesla.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.splittesla.app.navi.NaviHub
import com.splittesla.app.navi.NaviPhase
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.Glass
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.pressScale
import com.splittesla.core.navi.NaviPlace

/** Top bar button that opens [DestinationDialog]; [label] off leaves just the magnifier. */
@Composable
fun SearchPill(onClick: () -> Unit, label: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(source)
            .glass(50.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = if (label) 12.dp else 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, "목적지 검색", Modifier.size(18.dp), Neon.text)
        if (label) {
            Spacer(Modifier.width(6.dp))
            Text("목적지", fontSize = 12.sp, color = Neon.muted)
        }
    }
}

/**
 * Destination entry from the top bar: type an address or a place, pick a hit
 * and start guidance without leaving the tab in front. While guiding, a hit
 * becomes a stop or replaces the destination.
 */
@Composable
fun DestinationDialog(onDismiss: () -> Unit) {
    val navi by NaviHub.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    // While guiding, the hit waiting for "stop or new destination?".
    var picked by remember { mutableStateOf<NaviPlace?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        NaviHub.start()
        onDismiss()
    }
    val idle = navi.busy == null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .imePadding()
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(Glass.dense, RoundedCornerShape(22.dp))
                .glass(22.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (navi.guiding) "목적지 변경 · 경유지" else "목적지",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neon.text, modifier = Modifier.weight(1f),
                )
                GlowButton("닫기", onDismiss)
            }
            if (!NaviHub.settings.hasMaps) {
                Hint("설정 탭 '길안내 API (네이버 · 충전소)'에 NAVER Cloud Maps 키를 넣으면 여기서 바로 길안내합니다.")
                return@Column
            }
            navi.busy?.let { Text("$it…", color = Neon.cyan, fontSize = 13.sp) }
            navi.message?.let { Text(it, color = Neon.amber, fontSize = 13.sp) }

            val stop = picked
            when {
                stop != null -> {
                    Column {
                        Text(stop.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Neon.text)
                        if (stop.address.isNotBlank()) Text(stop.address, fontSize = 12.sp, color = Neon.muted)
                    }
                    ButtonGrid(
                        idle,
                        "경유지로 추가" to { NaviHub.addStop(stop); onDismiss() },
                        "여기로 목적지 변경" to { NaviHub.replaceDestination(stop); onDismiss() },
                        "다시 고르기" to { picked = null },
                    )
                }
                navi.phase == NaviPhase.PREVIEW -> {
                    Text(navi.route?.goal?.name.orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Neon.text)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RouteChoices(navi)
                    }
                    ButtonGrid(idle, "안내 시작" to { permissions.launch(naviPermissions()) }, "다시 검색" to NaviHub::clear)
                }
                else -> {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    val search = { NaviHub.search(query) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(query, { query = it }, "주소 또는 장소", onDone = search, modifier = Modifier.weight(1f).focusRequester(focus))
                        GlowButton("검색", search, enabled = idle && query.isNotBlank(), filled = true)
                    }
                    if (!NaviHub.settings.hasSearch) {
                        Text("이름 검색 키가 없어 주소와 휴대폰 검색으로 찾습니다", fontSize = 12.sp, color = Neon.muted)
                    }
                    val choose: (NaviPlace) -> Unit = { p ->
                        if (navi.guiding) picked = p else NaviHub.preview(p)
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        navi.results.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(color = Neon.stroke)
                            PlaceRow(p, distanceFrom = navi.position ?: NaviHub.bestPosition()) { choose(p) }
                        }
                        val recent = remember(navi.results) { NaviHub.settings.recent }
                        if (navi.results.isEmpty() && recent.isNotEmpty()) {
                            Text("최근 목적지", style = Caption)
                            recent.forEach { p -> PlaceRow(p, null) { choose(p) } }
                        }
                    }
                }
            }
        }
    }
}
