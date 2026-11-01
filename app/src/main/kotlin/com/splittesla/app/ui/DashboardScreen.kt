package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.splittesla.app.AppViewModel
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.navi.NaviHub
import com.splittesla.app.navi.NaviUi
import kotlinx.coroutines.delay

/**
 * The cockpit: the speedometer as large as the screen allows, the next turn
 * and the speed camera beside it, and a portrait map. The only chrome is one
 * line of status icons whose menu button [onMenu] opens the app's places.
 *
 * It follows the window's shape as well as the layout picked in Settings (or
 * by a long press on the gauge):
 * - wide, a phone on its side: the A, B and C rows;
 * - squarish, an unfolded foldable or a tablet: the gauge over the turn and
 *   camera cards, the map full height beside them (on the left for B);
 * - tall, any portrait window: gauge, cards and map stacked.
 * On larger screens everything is scaled up together, up to 1.35×, so the
 * cards keep their proportions instead of becoming empty glass with small type.
 */
@Composable
fun DashboardScreen(vm: AppViewModel, ui: UiState, modifier: Modifier, onMenu: () -> Unit = {}) {
    KeepScreenOn()
    val camera by CameraState.status.collectAsState()
    val gpsSpeed by CameraState.gpsSpeedKmh.collectAsState()
    val navi by NaviHub.state.collectAsState()
    val demoTime by produceState(0f, ui.demo) {
        while (ui.demo) {
            value += 0.05f
            delay(50)
        }
    }
    val c = if (ui.demo) Cockpit.demo(demoTime) else Cockpit.from(ui, camera, gpsSpeed)

    BoxWithConstraints(modifier) {
        val base = LocalDensity.current
        val scale = (min(maxWidth, maxHeight) / 400.dp).coerceIn(1f, 1.35f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val aspect = maxWidth / maxHeight
                when {
                    aspect >= 1.7f -> WideCockpit(vm, ui, c, navi, onMenu, maxWidth)
                    aspect >= 0.85f -> SquareCockpit(vm, ui, c, navi, onMenu, maxHeight)
                    else -> TallCockpit(vm, ui, c, navi, onMenu, maxHeight)
                }
            }
        }
    }
}

private val gap = Arrangement.spacedBy(10.dp)

/**
 * The gauge as large as fits in [modifier]'s space while keeping the face's
 * proportions: limited by the height on a wide screen and by the width on a
 * narrow column, never spilling over its neighbours.
 */
@Composable
private fun FitGauge(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = ui.gaugeStyle.aspect
        val width = min(maxWidth, maxHeight * aspect)
        GaugeBox(vm, ui, c, Modifier.size(width, width / aspect))
    }
}

/** A phone on its side: one row per layout. */
@Composable
private fun WideCockpit(vm: AppViewModel, ui: UiState, c: Cockpit, navi: NaviUi, onMenu: () -> Unit, width: Dp) {
    if (ui.cockpitLayout == CockpitLayout.IMMERSIVE) {
        // C: the top line stays over the gauge so the map runs the full height.
        Row(Modifier.fillMaxSize(), horizontalArrangement = gap) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CockpitTopLine(vm, ui, c, onMenu)
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitGauge(vm, ui, c, Modifier.fillMaxHeight().widthIn(max = width * 0.45f))
                    CameraCard(vm, ui, c, Modifier.weight(1f), tall = true)
                }
            }
            Box(Modifier.weight(0.52f).fillMaxHeight()) {
                NaviMap(navi, Modifier.fillMaxSize())
                GuideCard(navi, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), overlay = true)
                TripCard(navi, c, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), overlay = true)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CockpitTopLine(vm, ui, c, onMenu)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
            if (ui.cockpitLayout == CockpitLayout.BALANCED) {
                FitGauge(vm, ui, c, Modifier.fillMaxHeight().widthIn(max = width * 0.5f))
                Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = gap) {
                    GuideCard(navi, Modifier.weight(1.15f).fillMaxWidth())
                    CameraCard(vm, ui, c, Modifier.weight(1.15f).fillMaxWidth())
                    TripCard(navi, c, Modifier.weight(0.8f).fillMaxWidth())
                }
                NaviMap(navi, Modifier.weight(1f).fillMaxHeight())
            } else {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
                    GuideCard(navi, Modifier.weight(1f).fillMaxWidth())
                    CameraCard(vm, ui, c, Modifier.weight(1f).fillMaxWidth())
                }
                FitGauge(vm, ui, c, Modifier.fillMaxHeight().widthIn(max = width * 0.45f))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    NaviMap(navi, Modifier.fillMaxSize())
                    TripCard(navi, c, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), overlay = true)
                }
            }
        }
    }
}

/**
 * An unfolded foldable or a tablet: the gauge with the camera sign standing
 * beside it, the next turn as one long line underneath, and the map full
 * height alongside (on the left for B).
 */
@Composable
private fun SquareCockpit(vm: AppViewModel, ui: UiState, c: Cockpit, navi: NaviUi, onMenu: () -> Unit, height: Dp) {
    val banner = (height * 0.13f).coerceIn(64.dp, 96.dp)
    val mapLeft = ui.cockpitLayout == CockpitLayout.CENTER
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CockpitTopLine(vm, ui, c, onMenu)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = gap) {
            if (mapLeft) MapWithTrip(navi, c, Modifier.weight(0.85f).fillMaxHeight())
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = gap) {
                GaugeWithCamera(vm, ui, c, Modifier.weight(1f).fillMaxWidth())
                GuideCard(navi, Modifier.height(banner).fillMaxWidth(), banner = true)
            }
            if (!mapLeft) MapWithTrip(navi, c, Modifier.weight(0.85f).fillMaxHeight())
        }
    }
}

/** Portrait: the gauge with its camera sign, the next turn in one line, then the map. */
@Composable
private fun TallCockpit(vm: AppViewModel, ui: UiState, c: Cockpit, navi: NaviUi, onMenu: () -> Unit, height: Dp) {
    val banner = (height * 0.09f).coerceIn(64.dp, 92.dp)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CockpitTopLine(vm, ui, c, onMenu)
        GaugeWithCamera(vm, ui, c, Modifier.weight(1.1f).fillMaxWidth())
        GuideCard(navi, Modifier.height(banner).fillMaxWidth(), banner = true)
        MapWithTrip(navi, c, Modifier.weight(1f).fillMaxWidth())
    }
}

/** The gauge with the speed camera sign standing in a narrow column beside it. */
@Composable
private fun GaugeWithCamera(vm: AppViewModel, ui: UiState, c: Cockpit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
        FitGauge(vm, ui, c, Modifier.weight(1f).fillMaxHeight())
        CameraCard(vm, ui, c, Modifier.weight(0.42f).fillMaxHeight(), tall = true)
    }
}

@Composable
private fun MapWithTrip(navi: NaviUi, c: Cockpit, modifier: Modifier) {
    Box(modifier) {
        NaviMap(navi, Modifier.fillMaxSize())
        TripCard(navi, c, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), overlay = true)
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
