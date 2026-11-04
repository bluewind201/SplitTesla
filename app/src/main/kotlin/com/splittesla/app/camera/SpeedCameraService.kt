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
import android.os.SystemClock
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
import com.splittesla.core.navi.SpeedEvidence
import com.splittesla.core.camera.spokenText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var lastAcceptedFixMs = 0L

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
        if (speaker == null) start()
        // Not sticky: once the app is closed the system must not bring the camera watch back by itself.
        return START_NOT_STICKY
    }

    private fun start() {
        CameraState.running.value = true
        // The shared speaker: the voice picked in Settings, and music ducked while it talks.
        speaker = Speaker(this, priority = 60)
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
            while (isActive) {
                delay(1000)
                if (SystemClock.elapsedRealtime() - lastAcceptedFixMs > 3000) {
                    CameraState.gpsOk.value = false
                    CameraState.gpsSpeedKmh.value = null
                    alerter?.invalidateMeasurement()
                    alerter?.let { CameraState.status.value = it.status }
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val alerter = alerter ?: return
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        val valid = age in 0..3_000_000_000L && location.latitude.isFinite() && location.longitude.isFinite() &&
            (!location.hasAccuracy() || (location.accuracy.isFinite() && location.accuracy in 0f..60f))
        CameraState.gpsOk.value = valid
        if (!valid) {
            alerter.invalidateMeasurement()
            CameraState.gpsSpeedKmh.value = null
            CameraState.status.value = alerter.status
            return
        }
        val gpsSpeed = SpeedEvidence.measured(location.takeIf { it.hasSpeed() }?.speed?.times(3.6))
        val now = System.currentTimeMillis()
        val canSpeed = SpeedEvidence.fresh(CameraState.commanderSpeedKmh?.toDouble(), CameraState.commanderSpeedAt, now)
        val speed = canSpeed ?: gpsSpeed
        val heading = location.takeIf { it.hasBearing() && it.bearing.isFinite() }?.bearing?.toDouble()
        // Guiding: only the cameras on the route, unless we have left it.
        val navi = NaviHub.state.value
        if (navi.cameras !== routeCameras) {
            routeCameras = navi.cameras
            routeCameraIds = navi.cameras.map { it.camera.id }.toSet()
        }
        alerter.routeCameraIds = routeCameraIds.takeIf { navi.guiding && navi.progress?.offRoute != true }
        val roadLimit = CameraState.commanderLimitKmh
            ?.takeIf { canSpeed != null && repo?.roadLimitCheck == true }
            ?.toInt()?.takeIf { it in 20..130 }
        alerter.warningPreset = repo?.warningPreset ?: com.splittesla.core.camera.CameraWarningPreset.SPEED
        val alerts = alerter.update(location.latitude, location.longitude, heading, speed ?: Double.NaN,
            location.elapsedRealtimeNanos / 1_000_000, roadLimit, measuredSpeedKmh = speed,
            accuracyM = location.takeIf { it.hasAccuracy() }?.accuracy?.toDouble())
        CameraState.gpsOk.value = alerter.lastFixAccepted
        if (alerter.lastFixAccepted) {
            lastAcceptedFixMs = location.elapsedRealtimeNanos / 1_000_000
            CameraState.fix = location
            CameraState.gpsSpeedKmh.value = gpsSpeed?.toFloat()
        } else CameraState.gpsSpeedKmh.value = null
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
    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        CameraState.gpsOk.value = false
        alerter?.invalidateMeasurement()
        CameraState.lastAlert.value = "GPS가 꺼져 있습니다"
    }

    @Deprecated("Required on Android 10 and older")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun announce(alert: CameraAlert) {
        val text = alert.spokenText()
        CameraState.lastAlert.value = text
        speaker?.speak(text, key = "camera:${alert.camera.id}:${alert::class.simpleName}:${if (alert is CameraAlert.Approaching) (alert.distanceM / 100).toInt() else 0}",
            priority = if (alert is CameraAlert.Final) 100 else 60,
            ttlMs = if (alert is CameraAlert.Final) 3000 else 6000, group = "camera:${alert.camera.id}")
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
