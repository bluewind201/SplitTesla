package com.splittesla.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.splittesla.app.MainActivity
import com.splittesla.app.R
import com.splittesla.app.ui.DriveDashboard
import com.splittesla.app.ui.DriveTopBar
import com.splittesla.app.ui.rememberCockpit
import com.splittesla.app.ui.theme.Glass
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.SplitTheme
import com.splittesla.app.ui.theme.glassBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Draws the dashboard over other apps, docked on the left at 3/5 of the
 * screen's width and its full height, while this app is out of sight. A
 * foreground service, so Android keeps the app (and its car links) alive
 * while the navigation app has the screen. Stops with the app's task.
 */
class DashboardOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private var view: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windows = getSystemService(WindowManager::class.java)
        startInForeground()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        scope.launch {
            combine(DashboardOverlay.appVisible, DashboardOverlay.dismissed) { app, closed -> !app && !closed }
                .collect { show -> if (show) show() else hide() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "내비 위 계기판", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, openAppIntent(), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("스플릿테슬라 계기판")
            .setContentText("앱이 뒤에 있으면 계기판을 내비 위에 띄웁니다")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun openAppIntent() = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun show() {
        val vm = DashboardOverlay.vm ?: return
        if (view != null || !DashboardOverlay.canDraw(this)) return
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DashboardOverlayService)
            setViewTreeSavedStateRegistryOwner(this@DashboardOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SplitTheme {
                    val ui by vm.ui.collectAsState()
                    val c = rememberCockpit(ui)
                    val shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .background(Color(0xFF0B0E13))
                            .glassBackdrop()
                            .border(1.dp, Glass.edgeLit, shape),
                    ) {
                        DriveDashboard(vm, ui, c, Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
                            DriveTopBar(vm, ui, c) {
                                GlowButton("앱 열기", { startActivity(openAppIntent()) }, accent = Neon.cyan)
                                GlowButton("✕", { DashboardOverlay.dismiss() })
                            }
                        }
                    }
                }
            }
        }
        try {
            windows.addView(compose, layoutParams())
            view = compose
            vm.overlayShown = true
        } catch (e: Exception) {
            vm.log("계기판 오버레이 표시 실패: ${e.message}")
        }
    }

    private fun hide() {
        val v = view ?: return
        view = null
        DashboardOverlay.vm?.overlayShown = false
        runCatching { windows.removeView(v) }
    }

    /** Left edge, 3/5 of the screen's width, full height. */
    private fun layoutParams() = WindowManager.LayoutParams(
        overlayWidth(),
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.LEFT or Gravity.TOP
        x = 0
        y = 0
        title = "SplitTesla dashboard"
    }

    @Suppress("DEPRECATION")
    private fun overlayWidth(): Int {
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windows.currentWindowMetrics.bounds.width()
        } else {
            val m = android.util.DisplayMetrics()
            windows.defaultDisplay.getRealMetrics(m)
            m.widthPixels
        }
        return (screen * WIDTH_FRACTION).toInt()
    }

    /** Turning the phone changes the screen's width; keep the dashboard at 3/5 of it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        runCatching { windows.updateViewLayout(v, layoutParams()) }
    }

    /** Swiped away from recent apps: the dashboard goes with the app. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        hide()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        hide()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "dashboard_overlay"
        const val NOTIFICATION_ID = 42
        const val WIDTH_FRACTION = 0.6f
    }
}
