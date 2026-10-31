package com.splittesla.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.ui.theme.Glass
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.glassBackdrop
import com.splittesla.app.ui.theme.LinkPill
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Rajdhani
import com.splittesla.app.ui.theme.SplitTheme
import com.splittesla.app.ui.theme.pressScale

private enum class Tab(val title: String, val english: String) {
    DASH("계기판", "COCKPIT"),
    CAR("차량", "VEHICLE"),
    S3XY("S3XY", "S3XY COMMANDER"),
    NAV("내비", "NAVIGATION"),
    AI("AI", "AI ASSISTANT"),
    DATA("데이터", "TELEMETRY"),
    LOGBOOK("차계부", "LOGBOOK"),
    SETTINGS("설정", "SETTINGS"),
}

/** Landscape shell: navigation rail on the left, slim status bar on top. */
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
            // Split screen beside the navi shows the dashboard alone; a very narrow window only the strip.
            BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < 380.dp) {
                CompactPanel(vm, ui)
            } else if (multiWindow && !menuInSplit) {
                SplitDashboard(vm, ui) { menuInSplit = true }
            } else if (tab == Tab.DASH && ui.cockpitLayout == CockpitLayout.IMMERSIVE) {
                // Layout C: the dashboard has the whole screen; its menu button slides the tabs in.
                var drawer by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    DashboardScreen(vm, ui, Modifier.fillMaxSize().padding(8.dp)) { drawer = true }
                    if (drawer) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { drawer = false })
                        Rail(tab) {
                            tabIndex = it.ordinal
                            drawer = false
                        }
                    }
                }
            } else Row(Modifier.fillMaxSize()) {
                Rail(tab) { tabIndex = it.ordinal }
                Column(Modifier.fillMaxSize()) {
                    TopStrip(tab, vm, ui, if (multiWindow) ({ menuInSplit = false }) else null)
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                        label = "tab",
                        modifier = Modifier.fillMaxSize(),
                    ) { t ->
                        val modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 16.dp, bottom = 12.dp)
                        when (t) {
                            Tab.DASH -> DashboardScreen(vm, ui, modifier)
                            Tab.CAR -> CarScreen(vm, ui, modifier)
                            Tab.S3XY -> ActionsScreen(vm, ui, modifier)
                            Tab.NAV -> NavScreen(vm, ui, modifier)
                            Tab.AI -> AssistantScreen(vm, modifier)
                            Tab.DATA -> TelemetryScreen(ui, modifier)
                            Tab.LOGBOOK -> LogbookScreen(vm, modifier)
                            Tab.SETTINGS -> SettingsScreen(vm, ui, modifier)
                        }
                    }
                }
            }
            }
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
private fun Rail(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        // Eight tabs can outgrow a phone held sideways, so the rail scrolls when it must.
        Modifier
            .fillMaxHeight()
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .width(76.dp)
            .glass(22.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Logo()
        Tab.entries.forEach { t -> RailItem(t, t == selected) { onSelect(t) } }
    }
}

@Composable
private fun Logo() {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("S", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Neon.lime)
        Text("/", fontFamily = Orbitron, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Neon.text)
    }
}

@Composable
private fun RailItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (selected) Neon.lime else Neon.muted, tween(200), label = "tint")
    val bar by animateDpAsState(if (selected) 22.dp else 0.dp, tween(220), label = "bar")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(bar).background(Neon.lime, RoundedCornerShape(2.dp)))
        Column(
            Modifier
                .padding(start = 6.dp)
                .pressScale(source, 0.9f)
                .then(if (selected) Modifier.glass(14.dp, tint = Neon.lime.copy(alpha = 0.6f), edge = Neon.lime.copy(alpha = 0.45f)) else Modifier)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TabIcon(tab, tint)
            Text(tab.title, fontSize = 11.sp, color = tint, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
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
        Tab.NAV -> Icon(Icons.Filled.Place, null, m, tint)
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

@Composable
private fun TopStrip(tab: Tab, vm: AppViewModel, ui: UiState, onSplitDash: (() -> Unit)?) {
    val commander = ui.commander
    val car = ui.car
    val busy = ui.busy
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (tab == Tab.DASH) {
            // The dashboard's title gives way to its status icons.
            DashStatus(vm, ui, Modifier.weight(1f))
        } else {
            Column {
                Text(tab.english, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 3.sp, color = Neon.muted)
                Text(tab.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neon.text)
            }
            Spacer(Modifier.weight(1f))
        }
        onSplitDash?.let { GlowButton("계기판만", it, accent = Neon.cyan) }
        if (busy != null) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Neon.lime, strokeWidth = 2.dp)
            Text("$busy…", fontSize = 12.sp, color = Neon.muted)
        }
        var searching by remember { mutableStateOf(false) }
        SearchPill({ searching = true })
        if (searching) DestinationDialog { searching = false }
        LinkPill("S3XY", commander.label, commander.color(), commander == LinkState.CONNECTING || commander == LinkState.NEEDS_PAIRING) {
            if (commander == LinkState.CONNECTED) vm.disconnectCommander() else vm.connectCommander()
        }
        LinkPill("CAR", car.label, car.color(), car == LinkState.CONNECTING) {
            if (car == LinkState.CONNECTED) vm.disconnectCar() else vm.connectCar()
        }
        StatusClock()
    }
}

@Composable
private fun DashStatus(vm: AppViewModel, ui: UiState, modifier: Modifier) {
    val camera by CameraState.status.collectAsState()
    val gpsSpeed by CameraState.gpsSpeedKmh.collectAsState()
    CockpitStatus(vm, ui, Cockpit.from(ui, camera, gpsSpeed), modifier)
}

fun LinkState.color(): Color = when (this) {
    LinkState.CONNECTED -> Neon.green
    LinkState.CONNECTING -> Neon.amber
    LinkState.NEEDS_PAIRING -> Neon.cyan
    LinkState.FAILED -> Neon.red
    LinkState.DISCONNECTED -> Neon.faint
}
