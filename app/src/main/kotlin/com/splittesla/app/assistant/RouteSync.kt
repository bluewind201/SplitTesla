package com.splittesla.app.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.splittesla.app.AppViewModel
import com.splittesla.app.LinkState
import com.splittesla.app.R
import com.splittesla.app.ui.NaviLaunch
import com.splittesla.core.assistant.LatLon
import com.splittesla.core.assistant.RouteShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sends the car's destination to the chosen phone navigation app whenever it
 * changes on the car, with no button press.
 *
 * Only this direction exists: the car's route is read over BLE (the car
 * doesn't push it, so it is polled every 10 s), and the phone app is started
 * with a deep link to the same point, where it works out its own route.
 * Android lets an app open another one from the background only with the
 * "display over other apps" permission, so without it a notification asks
 * for one tap instead.
 */
class RouteSync(private val vm: AppViewModel, private val scope: CoroutineScope) {
    private val context = vm.getApplication<Application>()
    private val prefs = AssistantPrefs(context)
    private var sent: LatLon? = null

    private val _last = MutableStateFlow<String?>(null)
    /** What was sent last, for the settings screen. */
    val last: StateFlow<String?> = _last.asStateFlow()

    fun start() {
        // The route has to be read from the car regularly to notice a change.
        scope.launch {
            vm.ui.map { it.car }.distinctUntilChanged().collect { car ->
                if (car == LinkState.CONNECTED && prefs.autoShareRoute && !vm.ui.value.navAutoRefresh) vm.setNavAutoRefresh(true)
            }
        }
        scope.launch {
            vm.ui.map { ui ->
                val nav = ui.nav?.takeIf { it.hasRoute }
                val lat = nav?.destinationLat
                val lon = nav?.destinationLon
                if (lat == null || lon == null) null else LatLon(lat, lon) to (nav?.destination ?: "테슬라 목적지")
            }.distinctUntilChanged().collect(::onDestination)
        }
    }

    private fun onDestination(dest: Pair<LatLon, String>?) {
        if (dest == null) {
            // Route cancelled on the car: the same place set again later is sent again.
            sent = null
            return
        }
        if (!prefs.autoShareRoute || !RouteShare.isNewDestination(sent, dest.first)) return
        share(dest.first, dest.second)
    }

    fun canOpenFromBackground(): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun share(point: LatLon, name: String) {
        sent = point
        val app = prefs.naviApp
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
        if (vm.isForeground || canOpenFromBackground()) {
            try {
                NaviLaunch.route(context, app, point.lat, point.lon, name, adjacent = vm.inMultiWindow)
                _last.value = "$time  $name → ${app.label} 전송"
                vm.log("테슬라 목적지 '$name'을(를) ${app.label}(으)로 자동 전송")
                return
            } catch (e: Exception) {
                vm.log("${app.label} 자동 실행 실패, 알림으로 대신 보냅니다: ${e.message}")
            }
        }
        notify(app, point, name)
        _last.value = "$time  $name → 알림으로 전송 (탭하면 ${app.label} 안내)"
    }

    private fun notify(app: NaviApp, point: LatLon, name: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "테슬라 목적지 공유", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 0, NaviLaunch.routeIntent(context, app, point.lat, point.lon, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("테슬라 목적지가 바뀌었습니다")
            .setContentText("$name · 탭하면 ${app.label}에서 안내합니다")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            vm.log("알림 권한이 없어 목적지를 전하지 못했습니다")
        }
        vm.log("앱이 뒤에 있어 '$name' 안내를 알림으로 보냈습니다")
    }

    private companion object {
        const val CHANNEL = "route_share"
        const val NOTIFICATION_ID = 11
    }
}
