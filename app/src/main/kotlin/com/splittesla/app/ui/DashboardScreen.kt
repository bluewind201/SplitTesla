package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.splittesla.app.AppViewModel
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.navi.NaviHub
import kotlinx.coroutines.delay

/**
 * The cockpit in the layout picked in Settings (or by a long press on the
 * gauge): the speedometer as large as the screen allows, the next turn and the
 * speed camera beside it, and a portrait map. Weather, wind, battery, area and
 * locks are small icons in the top bar; layout C hides the tabs and carries
 * them in its own top line, with [onMenu] bringing the tabs back.
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
        // A wide face (F1) still has to leave room for the turn, the camera and the map.
        fun gauge(share: Float) = Modifier
            .fillMaxHeight()
            .widthIn(max = maxWidth * share)
            .aspectRatio(ui.gaugeStyle.aspect, matchHeightConstraintsFirst = true)
        val gap = Arrangement.spacedBy(10.dp)
        when (ui.cockpitLayout) {
            CockpitLayout.BALANCED -> Row(Modifier.fillMaxSize(), horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                GaugeBox(vm, ui, c, gauge(0.5f))
                Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = gap) {
                    GuideCard(navi, Modifier.weight(1.15f).fillMaxWidth())
                    CameraCard(vm, ui, c, Modifier.weight(1.15f).fillMaxWidth())
                    TripCard(navi, c, Modifier.weight(0.8f).fillMaxWidth())
                }
                NaviMap(navi, Modifier.weight(1f).fillMaxHeight())
            }
            CockpitLayout.CENTER -> Row(Modifier.fillMaxSize(), horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
                    GuideCard(navi, Modifier.weight(1f).fillMaxWidth())
                    CameraCard(vm, ui, c, Modifier.weight(1f).fillMaxWidth())
                }
                GaugeBox(vm, ui, c, gauge(0.45f))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    NaviMap(navi, Modifier.fillMaxSize())
                    TripCard(navi, c, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), overlay = true)
                }
            }
            CockpitLayout.IMMERSIVE -> Row(Modifier.fillMaxSize(), horizontalArrangement = gap) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ImmersiveTopLine(vm, ui, c, onMenu)
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GaugeBox(vm, ui, c, gauge(0.45f))
                        CameraCard(vm, ui, c, Modifier.weight(1f), tall = true)
                    }
                }
                Box(Modifier.weight(0.52f).fillMaxHeight()) {
                    NaviMap(navi, Modifier.fillMaxSize())
                    GuideCard(navi, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), overlay = true)
                    TripCard(navi, c, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), overlay = true)
                }
            }
        }
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
