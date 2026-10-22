package com.splittesla.app.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.splittesla.app.MainActivity
import com.splittesla.app.R

/**
 * Keeps the microphone allowed while Naver Map or Kakao Map is in front.
 * Android cuts background apps off the mic unless a microphone foreground
 * service is running. The listening itself stays in [DriveAssistant].
 */
class AssistantService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TALK) {
            onTalk?.invoke()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "음성 비서", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val talk = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("음성 비서 대기 중")
            .setContentText("호출어로 부르세요. 영상·음악이 나올 때는 '말하기'를 누르세요")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "말하기", talk).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, notification)
            }
        } catch (e: Exception) {
            // Started from the background or without the mic permission: listening still works while the app is open.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "assistant"
        private const val ID = 7
        private const val ACTION_TALK = "com.splittesla.app.TALK"

        /** Set by the assistant; the notification's "말하기" button calls it. */
        @Volatile var onTalk: (() -> Unit)? = null

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AssistantService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantService::class.java))
        }
    }
}
