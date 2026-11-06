package com.splittesla.app.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.splittesla.app.AppViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The dashboard that floats over the navigation app. The service starts while
 * the app is on screen (Android lets a foreground service start only then)
 * and stays for as long as the app runs; the window itself is shown only
 * while the app is out of sight, and goes away with the app.
 */
object DashboardOverlay {
    /** The app's view model, which the floating dashboard reads. Set while the activity exists. */
    @Volatile
    var vm: AppViewModel? = null

    private val _appVisible = MutableStateFlow(true)

    /** The app's own screen is showing, so the floating copy is not needed. */
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    private val _dismissed = MutableStateFlow(false)

    /** The driver closed the floating dashboard; it stays away until the app is opened again. */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** The system screen for "display over other apps", opened at this app. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app came on screen. */
    fun onAppShown() {
        _dismissed.value = false
        _appVisible.value = true
    }

    /** The app went out of sight (home, another app, the screen turned off). */
    fun onAppHidden() {
        _appVisible.value = false
    }

    fun dismiss() {
        _dismissed.value = true
    }

    /**
     * Starts or stops the service to match the setting and the permission.
     * Call while the app is on screen.
     */
    fun sync(context: Context, enabled: Boolean) {
        if (enabled && canDraw(context)) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DashboardOverlayService::class.java))
            } catch (e: Exception) {
                vm?.log("계기판 오버레이 시작 실패: ${e.message}")
            }
        } else {
            stop(context)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, DashboardOverlayService::class.java))
    }
}
