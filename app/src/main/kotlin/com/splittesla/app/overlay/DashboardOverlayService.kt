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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.splittesla.app.ui.Cockpit
import com.splittesla.app.ui.gauge.GaugeStyle
import com.splittesla.app.ui.gauge.Speedometer
import kotlin.math.sqrt
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
import com.splittesla.app.ui.rememberCockpit
import com.splittesla.app.ui.theme.Glass
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.SplitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Draws the speedometer over other apps in a small window the driver can
 * drag anywhere (a third of the width in landscape, a sixth of the screen in
 * portrait), while this app is out of sight. A
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
        val p = layoutParams(vm.ui.value.gaugeStyle.aspect)
        params = p
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DashboardOverlayService)
            setViewTreeSavedStateRegistryOwner(this@DashboardOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SplitTheme {
                    val ui by vm.ui.collectAsState()
                    val c = rememberCockpit(ui)
                    val shape = RoundedCornerShape(18.dp)
                    // Only the gauge: the car's state and the weather are spoken by the assistant.
                    // Drag anywhere to move it off what the navi needs.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .background(Color(0xE60B0E13))
                            .border(1.dp, Glass.edgeLit, shape)
                            .pointerInput(Unit) {
                                detectDragGestures(onDragEnd = { savePosition() }) { change, drag ->
                                    change.consume()
                                    moveBy(drag.x, drag.y)
                                }
                            },
                    ) {
                        OverlayGauge(ui.gaugeStyle, c, Modifier.fillMaxSize().padding(6.dp))
                        CornerButton("↗", Modifier.align(Alignment.TopStart)) { startActivity(openAppIntent()) }
                        CornerButton("✕", Modifier.align(Alignment.TopEnd)) { DashboardOverlay.dismiss() }
                    }
                }
            }
        }
        try {
            windows.addView(compose, p)
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

    private var params: WindowManager.LayoutParams? = null

    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        val p = params ?: return
        val (w, h) = screenSize()
        p.x = (p.x + dx.toInt()).coerceIn(0, (w - p.width).coerceAtLeast(0))
        p.y = (p.y + dy.toInt()).coerceIn(0, (h - p.height).coerceAtLeast(0))
        runCatching { windows.updateViewLayout(v, p) }
    }

    private val positions by lazy { getSharedPreferences("overlay", MODE_PRIVATE) }

    private fun orientationKey(): String {
        val (w, h) = screenSize()
        return if (w >= h) "land" else "port"
    }

    /** Remembers where the driver left it, separately for landscape and portrait. */
    private fun savePosition() {
        val p = params ?: return
        val k = orientationKey()
        positions.edit().putInt("x_$k", p.x).putInt("y_$k", p.y).apply()
    }

    /**
     * Landscape: a third of the screen's width. Portrait: a sixth of the
     * screen's area. The height follows the gauge face's shape ([aspect] is
     * width over height). Starts at the left edge, centred vertically, or
     * wherever it was last dragged to.
     */
    private fun layoutParams(aspect: Float): WindowManager.LayoutParams {
        val (sw, sh) = screenSize()
        val landscape = sw >= sh
        var w: Int
        var h: Int
        if (landscape) {
            w = sw / 3
            h = (w / aspect).toInt()
        } else {
            h = sqrt(sw.toDouble() * sh / 6.0 / aspect).toInt()
            w = (h * aspect).toInt()
        }
        if (h > sh) { h = sh; w = (h * aspect).toInt() }
        if (w > sw) { w = sw; h = (w / aspect).toInt() }
        val k = if (landscape) "land" else "port"
        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.LEFT or Gravity.TOP
            x = positions.getInt("x_$k", 0).coerceIn(0, (sw - w).coerceAtLeast(0))
            y = positions.getInt("y_$k", (sh - h) / 2).coerceIn(0, (sh - h).coerceAtLeast(0))
            title = "SplitTesla dashboard"
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windows.currentWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        val m = android.util.DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(m)
        m.widthPixels to m.heightPixels
    }

    /** Turning the phone: resize for the new orientation and go to its remembered spot. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        val p = layoutParams(DashboardOverlay.vm?.ui?.value?.gaugeStyle?.aspect ?: 1f)
        params = p
        runCatching { windows.updateViewLayout(v, p) }
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
    }
}

/** The face is drawn for about 320 dp; a smaller window scales it down as a whole. */
@Composable
private fun OverlayGauge(style: GaugeStyle, c: Cockpit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val base = LocalDensity.current
        val scale = (min(maxWidth, maxHeight) / 320.dp).coerceIn(0.4f, 2f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            Speedometer(style, c, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CornerButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(4.dp)
            .size(30.dp)
            .background(Glass.dense, CircleShape)
            .border(1.dp, Glass.edgeLit, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = Neon.text)
    }
}
