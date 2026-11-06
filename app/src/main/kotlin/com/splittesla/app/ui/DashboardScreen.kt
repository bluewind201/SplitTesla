package com.splittesla.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.splittesla.app.AppViewModel
import com.splittesla.app.UiState

/**
 * The cockpit tab: the speedometer as large as the screen allows, with the
 * car's state and the weather beside it. Route guidance is left to the
 * phone's navigation app; when this app goes to the background the same
 * dashboard floats over it. The only chrome is one line of status icons whose
 * menu button [onMenu] opens the app's places.
 *
 * On larger screens everything is scaled up together, up to 1.35×, so the
 * cards keep their proportions instead of becoming empty glass with small type.
 */
@Composable
fun DashboardScreen(vm: AppViewModel, ui: UiState, modifier: Modifier, onMenu: () -> Unit = {}) {
    KeepScreenOn()
    val c = rememberCockpit(ui)
    BoxWithConstraints(modifier) {
        val base = LocalDensity.current
        val scale = (min(maxWidth, maxHeight) / 400.dp).coerceIn(1f, 1.35f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            DriveDashboard(vm, ui, c, Modifier.fillMaxSize()) { CockpitTopLine(vm, ui, c, onMenu) }
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
