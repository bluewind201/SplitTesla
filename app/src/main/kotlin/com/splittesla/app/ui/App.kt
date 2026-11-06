package com.splittesla.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.ui.theme.Glass
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.LinkPill
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.SplitTheme
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.glassBackdrop
import com.splittesla.app.ui.theme.pressScale

/** Top-level places. The [primary] four are always in reach; the rest live under 더보기. */
private enum class Tab(val title: String, val english: String, val primary: Boolean) {
    DASH("계기판", "COCKPIT", true),
    CAR("차량", "VEHICLE", true),
    S3XY("S3XY", "S3XY COMMANDER", true),
    AI("AI 비서", "AI ASSISTANT", true),
    DATA("데이터", "TELEMETRY", false),
    LOGBOOK("차계부", "LOGBOOK", false),
    SETTINGS("설정", "SETTINGS", false),
}

/**
 * The shell. Following Android's adaptive layout guidance, navigation sits at
 * the edge the hands reach: a rail beside a landscape phone, an unfolded
 * foldable or a tablet, a bar along the bottom of a narrow portrait window,
 * and out of sight on the dashboard, where it would only distract; its menu
 * button brings up every place at once.
 */
@Composable
fun SplitTeslaApp(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[tabIndex]
    val snackbar = remember { SnackbarHostState() }
    val multiWindow by vm.multiWindow.collectAsState()
    // The tabs were asked for while split; leaving split screen resets it.
    var menuInSplit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(multiWindow) { if (!multiWindow) menuInSplit = false }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val select: (Tab) -> Unit = {
        tabIndex = it.ordinal
        menuOpen = false
    }
    LaunchedEffect(ui.toast) {
        ui.toast?.let {
            vm.consumeToast()
            snackbar.currentSnackbarData?.dismiss()
            // Registration results stay up longer so they aren't missed.
            snackbar.showSnackbar(it, duration = if (it.startsWith("✅") || it.startsWith("❌")) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }

    SplitTheme {
        Box(Modifier.fillMaxSize().glassBackdrop()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val splitDash: (() -> Unit)? = if (multiWindow) ({ menuInSplit = false }) else null
                val roomForPills = maxWidth >= 760.dp
                // Split screen beside the navi shows the dashboard alone; a very narrow window only the strip.
                when {
                    maxWidth < 380.dp -> CompactPanel(vm, ui)
                    multiWindow && !menuInSplit -> SplitDashboard(vm, ui) { menuInSplit = true }
                    tab == Tab.DASH -> DashboardScreen(vm, ui, Modifier.fillMaxSize().padding(8.dp)) { menuOpen = true }
                    maxWidth < 600.dp && maxHeight > maxWidth -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(start = 12.dp)) { TopStrip(tab, vm, ui, compact = true, splitDash) }
                        TabContent(tab, vm, ui, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                        BottomBar(tab, select) { menuOpen = true }
                    }
                    else -> Row(Modifier.fillMaxSize()) {
                        Rail(tab, select) { menuOpen = true }
                        Column(Modifier.fillMaxSize()) {
                            TopStrip(tab, vm, ui, compact = !roomForPills, splitDash)
                            TabContent(tab, vm, ui, Modifier.fillMaxSize().padding(start = 4.dp, end = 16.dp, bottom = 12.dp))
                        }
                    }
                }
            }
            if (menuOpen) MenuSheet(tab, select) { menuOpen = false }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { data ->
                Snackbar(
                    data,
                    containerColor = Glass.dense,
                    contentColor = Neon.text,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
    }
}

@Composable
private fun TabContent(tab: Tab, vm: AppViewModel, ui: UiState, modifier: Modifier) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
        label = "tab",
        modifier = modifier,
    ) { t ->
        val m = Modifier.fillMaxSize()
        when (t) {
            Tab.DASH -> DashboardScreen(vm, ui, m)
            Tab.CAR -> CarScreen(vm, ui, m)
            Tab.S3XY -> ActionsScreen(vm, ui, m)
            Tab.AI -> AssistantScreen(vm, m)
            Tab.DATA -> TelemetryScreen(ui, m)
            Tab.LOGBOOK -> LogbookScreen(vm, m)
            Tab.SETTINGS -> SettingsScreen(vm, ui, m)
        }
    }
}

/** Side rail: the four main places and 더보기, which shows the current one when it is a 더보기 place. */
@Composable
private fun Rail(selected: Tab, onSelect: (Tab) -> Unit, onMore: () -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .width(72.dp)
            .glass(22.dp)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        NavItems(selected, onSelect, onMore, Modifier)
    }
}

/** Bottom bar for a narrow portrait window, same five places as the rail. */
@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(64.dp)
            .glass(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItems(selected, onSelect, onMore, Modifier.weight(1f))
    }
}

@Composable
private fun NavItems(selected: Tab, onSelect: (Tab) -> Unit, onMore: () -> Unit, itemModifier: Modifier) {
    Tab.entries.filter { it.primary }.forEach { t ->
        NavItem(t.title, t == selected, { TabIcon(t, it) }, itemModifier) { onSelect(t) }
    }
    val other = selected.takeIf { !it.primary }
    NavItem(
        other?.title ?: "더보기",
        other != null,
        { tint -> if (other != null) TabIcon(other, tint) else MoreIcon(tint) },
        itemModifier,
        onMore,
    )
}

/** Icon in a pill that lights up when selected, label underneath; a 48 dp target. */
@Composable
private fun NavItem(label: String, selected: Boolean, icon: @Composable (Color) -> Unit, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (selected) Neon.lime else Neon.muted, tween(200), label = "tint")
    Column(
        modifier
            .pressScale(source, 0.9f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 30.dp)
                .then(if (selected) Modifier.glass(15.dp, tint = Neon.lime.copy(alpha = 0.55f), edge = Neon.lime.copy(alpha = 0.5f)) else Modifier),
            contentAlignment = Alignment.Center,
        ) { icon(tint) }
        Text(label, fontSize = 11.sp, color = tint, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/**
 * Every place at once, in big tiles: opened by the dashboard's menu button
 * and by 더보기. Back or a tap outside closes it.
 */
@Composable
private fun MenuSheet(selected: Tab, onSelect: (Tab) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .background(Glass.dense, shape)
                .glass(24.dp)
                // Taps on the panel itself must not close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("메뉴", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neon.text, modifier = Modifier.weight(1f))
                GlowButton("닫기", onDismiss)
            }
            Tab.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t -> MenuTile(t, t == selected, Modifier.weight(1f)) { onSelect(t) } }
                }
            }
        }
    }
}

@Composable
private fun MenuTile(tab: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val tint = if (selected) Neon.lime else Neon.text
    Column(
        modifier
            .pressScale(source, 0.94f)
            .glass(18.dp, tint = if (selected) Neon.lime.copy(alpha = 0.5f) else null, edge = if (selected) Neon.lime.copy(alpha = 0.6f) else null)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { TabIcon(tab, tint) }
        Text(tab.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
        Text(tab.english, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = Neon.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoreIcon(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        for (i in 0..2) drawCircle(tint, 2.2.dp.toPx(), Offset(size.width * (0.2f + 0.3f * i), size.height / 2))
    }
}

@Suppress("DEPRECATION")
@Composable
private fun TabIcon(tab: Tab, tint: Color) {
    val m = Modifier.size(22.dp)
    when (tab) {
        Tab.DASH -> Canvas(m) {
            val s = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
            drawArc(tint, 150f, 240f, false, style = s)
            drawLine(tint, center, Offset(size.width * 0.78f, size.height * 0.28f), 2.2.dp.toPx(), StrokeCap.Round)
            drawCircle(tint, 2.5.dp.toPx())
        }
        Tab.CAR -> Canvas(m) {
            val w = size.width
            val h = size.height
            drawRoundRect(tint, Offset(w * 0.08f, h * 0.22f), Size(w * 0.84f, h * 0.56f), CornerRadius(h * 0.24f), style = Stroke(2.2.dp.toPx()))
            drawLine(tint, Offset(w * 0.62f, h * 0.30f), Offset(w * 0.62f, h * 0.70f), 2.dp.toPx())
            drawLine(tint, Offset(w * 0.32f, h * 0.30f), Offset(w * 0.32f, h * 0.70f), 2.dp.toPx())
        }
        Tab.S3XY -> Icon(Icons.Filled.Star, null, m, tint)
        Tab.AI -> Canvas(m) {
            // Speech bubble: the assistant talks, it no longer listens.
            val w = size.width
            val h = size.height
            val s = 2.dp.toPx()
            drawRoundRect(tint, Offset(w * 0.08f, h * 0.14f), Size(w * 0.84f, h * 0.56f), CornerRadius(h * 0.18f), style = Stroke(s))
            drawLine(tint, Offset(w * 0.30f, h * 0.70f), Offset(w * 0.22f, h * 0.90f), s, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.22f, h * 0.90f), Offset(w * 0.46f, h * 0.70f), s, StrokeCap.Round)
        }
        Tab.DATA -> Icon(Icons.Filled.List, null, m, tint)
        Tab.LOGBOOK -> Icon(Icons.Filled.DateRange, null, m, tint)
        Tab.SETTINGS -> Icon(Icons.Filled.Settings, null, m, tint)
    }
}

/** Title, then link state and the clock. [compact] trades the link pills for dots. */
@Composable
private fun TopStrip(tab: Tab, vm: AppViewModel, ui: UiState, compact: Boolean, onSplitDash: (() -> Unit)?) {
    val commander = ui.commander
    val car = ui.car
    val busy = ui.busy
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(tab.english, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 3.sp, color = Neon.muted, maxLines = 1)
            Text(tab.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neon.text, maxLines = 1)
        }
        onSplitDash?.let { GlowButton("계기판만", it, accent = Neon.cyan) }
        if (busy != null) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Neon.lime, strokeWidth = 2.dp)
            if (!compact) Text("$busy…", fontSize = 12.sp, color = Neon.muted, maxLines = 1)
        }
        if (compact) {
            StatusDot("S3XY", commander) { if (commander == LinkState.CONNECTED) vm.disconnectCommander() else vm.connectCommander() }
            StatusDot("CAR", car) { if (car == LinkState.CONNECTED) vm.disconnectCar() else vm.connectCar() }
        } else {
            LinkPill("S3XY", commander.label, commander.color(), commander == LinkState.CONNECTING || commander == LinkState.NEEDS_PAIRING) {
                if (commander == LinkState.CONNECTED) vm.disconnectCommander() else vm.connectCommander()
            }
            LinkPill("CAR", car.label, car.color(), car == LinkState.CONNECTING) {
                if (car == LinkState.CONNECTED) vm.disconnectCar() else vm.connectCar()
            }
        }
        StatusClock()
    }
}

fun LinkState.color(): Color = when (this) {
    LinkState.CONNECTED -> Neon.green
    LinkState.CONNECTING -> Neon.amber
    LinkState.NEEDS_PAIRING -> Neon.cyan
    LinkState.FAILED -> Neon.red
    LinkState.DISCONNECTED -> Neon.faint
}
