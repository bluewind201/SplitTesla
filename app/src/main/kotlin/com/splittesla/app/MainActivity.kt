package com.splittesla.app

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.splittesla.app.overlay.DashboardOverlay
import com.splittesla.app.ui.SplitTeslaApp

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var overlayHinted = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) viewModel.log("권한 거부됨: $denied. 차량 검색이 동작하지 않을 수 있습니다")
        viewModel.assistant.onAppStart()
        // Start only after the permission answer, so the first scan is not refused.
        viewModel.startAutoConnect()
        // Once per start, after the prompts: the floating dashboard needs one more permission.
        if (!overlayHinted && viewModel.ui.value.overlay && !DashboardOverlay.canDraw(this)) {
            overlayHinted = true
            viewModel.toast("내비 위에 계기판을 띄우려면 설정 탭 '내비 위 계기판'에서 권한을 허용하세요")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation(resources.configuration)
        // Car-mount layout: edge to edge with the status and navigation bars hidden (swipe to peek).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Location is needed on every version: scans that may see beacons (a Tesla is one) require it.
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // The notification keeps the floating dashboard (and the car links) running behind the navi.
        val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        permissionLauncher.launch(permissions + notify)
        DashboardOverlay.vm = viewModel
        viewModel.handleIncomingPaymentIntent(intent)
        setContent { SplitTeslaApp(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingPaymentIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Android 10+ allows clipboard access only once the window gains focus.
            viewModel.bookFromClipboard(silentIfEmpty = true)
        }
    }

    /**
     * Phones stay landscape for the car mount. A large screen (an unfolded
     * foldable, a tablet) is left free to turn, as Android's large-screen
     * guidance asks; every screen lays itself out for the shape it gets.
     */
    private fun applyOrientation(config: Configuration) {
        requestedOrientation = if (config.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        viewModel.inMultiWindow = isInMultiWindowMode
        DashboardOverlay.vm = viewModel
        DashboardOverlay.onAppShown()
        // Started while on screen, as Android requires; it shows its window only once the app is hidden.
        DashboardOverlay.sync(this, viewModel.ui.value.overlay)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        viewModel.inMultiWindow = isInMultiWindowMode
    }

    override fun onStop() {
        viewModel.setForeground(false)
        // Out of sight (home, the navi, screen off): float the dashboard. Not for a rotation.
        if (!isChangingConfigurations && !isFinishing) DashboardOverlay.onAppHidden()
        super.onStop()
    }

    override fun onDestroy() {
        // Closed for good (back or recents, not a rotation): the floating dashboard goes too.
        if (isFinishing) {
            DashboardOverlay.stop(this)
            DashboardOverlay.vm = null
        }
        super.onDestroy()
    }
}
