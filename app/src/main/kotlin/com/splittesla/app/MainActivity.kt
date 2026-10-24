package com.splittesla.app

import android.Manifest
import android.content.pm.PackageManager
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
import com.splittesla.app.assistant.AssistantPrefs
import com.splittesla.app.assistant.SplitLaunch
import com.splittesla.app.camera.SpeedCameraService
import com.splittesla.app.ui.SplitTeslaApp

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var splitPending = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) viewModel.log("권한 거부됨: $denied. 차량 검색이 동작하지 않을 수 있습니다")
        viewModel.assistant.onAppStart()
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        viewModel.resumeCameraAlerts(location)
        // Start only after the permission answer, so the first scan is not refused.
        viewModel.startAutoConnect()
        // After the permission prompt, so the split doesn't open under it.
        if (splitPending) {
            splitPending = false
            val companion = AssistantPrefs(this).companionNavi
            window.decorView.postDelayed({ SplitLaunch.open(this, companion, viewModel::log) }, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // Notifications carry the camera alerts and a changed Tesla destination.
        val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        permissionLauncher.launch(permissions + notify)
        // Fresh start only, not on rotation or when coming back from split screen.
        splitPending = savedInstanceState == null && !isInMultiWindowMode
        setContent { SplitTeslaApp(viewModel) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        viewModel.inMultiWindow = isInMultiWindowMode
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        viewModel.inMultiWindow = isInMultiWindowMode
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        // Closed with back or from recents (not a rotation): stop watching cameras too.
        // The alerts switch stays on, so they resume the next time the app is opened.
        if (isFinishing) SpeedCameraService.stop(this)
        super.onDestroy()
    }
}
