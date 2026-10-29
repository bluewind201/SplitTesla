package com.splittesla.app.navi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.splittesla.app.MainActivity
import com.splittesla.app.R
import com.splittesla.app.assistant.Speaker
import com.splittesla.core.navi.GuidanceAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps in-app guidance alive with the screen off or a map in front: phone
 * GPS once a second into [NaviHub], its spoken prompts out through the
 * navigation audio stream, and the next turn in the notification.
 */
class NaviService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speaker: Speaker? = null
    private var listening = false
    private val locations get() = getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NaviHub.stop("길안내를 종료했습니다")
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("길안내 준비 중"), type)
        } catch (e: Exception) {
            // Android 14 refuses a location service without the permission.
            NaviHub.stop("위치 권한이 없어 길안내를 시작하지 못했습니다")
            stopSelf()
            return START_NOT_STICKY
        }
        if (speaker == null) start()
        return START_NOT_STICKY
    }

    private fun start() {
        speaker = Speaker(this)
        scope.launch { NaviHub.speech.collect { speaker?.speak(it) } }
        scope.launch {
            NaviHub.state.map { it.guiding to banner(it) }.distinctUntilChanged().collect { (guiding, text) ->
                if (!guiding) stopSelf() else updateNotification(text)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            listening = true
        }
    }

    override fun onLocationChanged(location: Location) = NaviHub.onFix(location)

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Required on Android 10 and older")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun banner(s: NaviUi): String {
        val p = s.progress ?: return s.route?.goal?.name?.let { "$it 안내 중" } ?: "길안내 중"
        val guide = s.route?.guides?.getOrNull(p.nextGuideIndex ?: -1)
        val next = guide?.let { g ->
            val d = p.nextGuideDistanceM?.let { GuidanceAnnouncer.distanceWords(it) }.orEmpty()
            "${g.maneuver.symbol} $d ${GuidanceAnnouncer.speakable(g)}"
        }
        val left = "${"%.1f".format(p.remainingM / 1000)}km · ${NaviHub.minutes(p.remainingMs)}"
        return listOfNotNull(next, left).joinToString(" · ")
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "길안내", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NaviService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(NaviHub.state.value.route?.goal?.name?.let { "$it 길안내" } ?: "길안내")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "안내 종료", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    /** Swiped away from recents: the app is closed, so end guidance with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        NaviHub.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (listening) locations.removeUpdates(this)
        speaker?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "navi"
        private const val NOTIFICATION_ID = 12
        private const val ACTION_STOP = "stop"
    }
}
