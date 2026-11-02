package com.splittesla.app.camera

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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.splittesla.app.MainActivity
import com.splittesla.app.R
import com.splittesla.app.assistant.Speaker
import com.splittesla.app.navi.NaviHub
import com.splittesla.core.camera.CameraAlert
import com.splittesla.core.camera.CameraAlerter
import com.splittesla.core.camera.CameraIndex
import com.splittesla.core.navi.RouteCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Watches phone GPS while driving and speaks speed camera warnings. Runs as a
 * foreground service so it keeps working with the screen off or another app
 * (a map) in front.
 */
class SpeedCameraService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var alerter: CameraAlerter? = null
    private var repo: CameraRepository? = null

    // The guided route's cameras, as ids, rebuilt when NaviHub loads a new list.
    private var routeCameras: List<RouteCamera>? = null
    private var routeCameraIds: Set<String> = emptySet()
    private var speaker: Speaker? = null
    private val locations get() = getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("카메라 데이터를 불러오는 중"), type)
        if (alerter == null) start()
        // Not sticky: once the app is closed the system must not bring the camera watch back by itself.
        return START_NOT_STICKY
    }

    private fun start() {
        CameraState.running.value = true
        // The shared speaker: the voice picked in Settings, and music ducked while it talks.
        speaker = Speaker(this)
        scope.launch {
            val repo = CameraRepository(this@SpeedCameraService).also { this@SpeedCameraService.repo = it }
            val cameras = repo.load()
            if (cameras.isEmpty()) {
                CameraState.lastAlert.value = "카메라 데이터가 없습니다. 먼저 데이터를 가져오세요"
                stopSelf()
                return@launch
            }
            alerter = CameraAlerter(
                CameraIndex(cameras),
                ignored = repo::isIgnored,
                onMiss = { cam, heading ->
                    if (repo.learnMisses && repo.recordMiss(cam.id, heading)) {
                        CameraState.lastAlert.value = "옆 도로 카메라로 보여 이 방향에서는 더 알리지 않습니다: ${cam.place.ifBlank { cam.id }}"
                    }
                },
                onDrivenPast = { cam, heading -> repo.recordPass(cam.id, heading) },
            )
            if (ContextCompat.checkSelfPermission(this@SpeedCameraService, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                CameraState.lastAlert.value = "위치 권한이 없습니다"
                stopSelf()
                return@launch
            }
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this@SpeedCameraService, Looper.getMainLooper())
            updateNotification("카메라 ${cameras.size}개 감시 중")
        }
    }

    override fun onLocationChanged(location: Location) {
        val alerter = alerter ?: return
        CameraState.gpsOk.value = true
        CameraState.fix = location
        CameraState.gpsSpeedKmh.value = if (location.hasSpeed()) location.speed * 3.6f else null
        val now = System.currentTimeMillis()
        val canSpeed = CameraState.commanderSpeedKmh?.takeIf { now - CameraState.commanderSpeedAt < 3000 }
        val speed = canSpeed?.toDouble() ?: (location.speed * 3.6)
        val heading = if (location.hasBearing()) location.bearing.toDouble() else null
        // Guiding: only the cameras on the route, unless we have left it.
        val navi = NaviHub.state.value
        if (navi.cameras !== routeCameras) {
            routeCameras = navi.cameras
            routeCameraIds = navi.cameras.map { it.camera.id }.toSet()
        }
        alerter.routeCameraIds = routeCameraIds.takeIf { navi.guiding && navi.progress?.offRoute != true && it.isNotEmpty() }
        val roadLimit = CameraState.commanderLimitKmh
            ?.takeIf { canSpeed != null && repo?.roadLimitCheck == true }
            ?.toInt()?.takeIf { it in 20..130 }
        val alerts = alerter.update(location.latitude, location.longitude, heading, speed, now, roadLimit)
        CameraState.status.value = alerter.status
        alerts.forEach(::announce)
        val s = alerter.status
        updateNotification(
            when {
                s.sectionAverageKmh != null -> "구간단속 평균 ${s.sectionAverageKmh!!.roundToInt()} / 제한 ${s.sectionLimitKmh}" +
                    (s.sectionRemainingM?.let { " · 남은 ${"%.1f".format(it / 1000)}km" } ?: "")
                s.ahead != null -> "${s.distanceM!!.roundToInt()}m 앞 카메라 · 제한 ${s.ahead!!.limitKmh}"
                else -> "주변 카메라 감시 중"
            },
        )
    }

    // Pre-Android 11 framework interfaces have no default bodies for these.
    override fun onProviderEnabled(provider: String) {
        CameraState.gpsOk.value = true
    }

    override fun onProviderDisabled(provider: String) {
        CameraState.gpsOk.value = false
        CameraState.lastAlert.value = "GPS가 꺼져 있습니다"
    }

    @Deprecated("Required on Android 10 and older")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun announce(alert: CameraAlert) {
        val limit = alert.camera.limitKmh
        val zone = alert.camera.zone?.let { "$it " }.orEmpty()
        val kind = if (alert.camera.signal) "신호 과속" else "과속"
        val text = when (alert) {
            is CameraAlert.Approaching ->
                "${(alert.distanceM / 50).roundToInt() * 50}미터 앞 ${zone}$kind 카메라, 제한속도 $limit" + if (alert.overLimit) ". 속도를 줄이세요" else ""
            is CameraAlert.Final -> "100미터 앞 ${zone}단속 카메라, 제한속도 $limit" + if (alert.overLimit) ". 속도를 줄이세요" else ""
            is CameraAlert.SectionStart -> "구간단속 시작, 제한속도 $limit"
            is CameraAlert.SectionEnd -> "구간단속 종료, 평균 속도 ${alert.averageKmh.roundToInt()}킬로미터"
        }
        CameraState.lastAlert.value = text
        speaker?.speak(text, interrupt = true)
        val strong = alert is CameraAlert.Final || (alert is CameraAlert.Approaching && alert.overLimit)
        vibrate(if (strong) longArrayOf(0, 300, 150, 300, 150, 300) else longArrayOf(0, 250))
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "과속카메라 알림", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeedCameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("과속카메라 알림")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "끄기", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    /** Swiped away from recents: the app is closed, so stop watching. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        locations.removeUpdates(this)
        speaker?.shutdown()
        scope.cancel()
        CameraState.running.value = false
        CameraState.gpsOk.value = false
        CameraState.gpsSpeedKmh.value = null
        CameraState.fix = null
        CameraState.status.value = com.splittesla.core.camera.CameraStatus()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "speed_camera"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, SpeedCameraService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, SpeedCameraService::class.java))
    }
}
