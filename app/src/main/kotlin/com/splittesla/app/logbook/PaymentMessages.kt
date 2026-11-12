package com.splittesla.app.logbook

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.splittesla.core.logbook.MessageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Card, pay and KakaoTalk notifications. Every app's notification is read
 * on the phone only; the parser keeps nothing but car expenses (charging
 * operators from the user's list, 하이패스/통행료, parking, car wash).
 */
class PaymentNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        val text = PaymentMessages.notificationText(n.notification) ?: return
        val source = PaymentMessages.sourceOf(n.packageName)
        runCatching { LogbookController.get(this).onMessage(text, source, n.postTime) }
    }
}

/** Incoming SMS (the card company's approval texts, 하이패스 texts). */
class PaymentSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long text arrives in parts; join them per sender.
        parts.groupBy { it.originatingAddress }.forEach { (_, msgs) ->
            val body = msgs.joinToString("") { it.messageBody.orEmpty() }
            val time = msgs.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
            runCatching { LogbookController.get(context).onMessage(body, MessageSource.SMS, time) }
        }
    }
}

object PaymentMessages {
    private val SMS_APPS = setOf(
        "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms", "com.lge.message",
    )

    fun sourceOf(pkg: String): MessageSource = when {
        pkg == "com.kakao.talk" -> MessageSource.KAKAO
        pkg in SMS_APPS -> MessageSource.SMS
        else -> MessageSource.NOTIFICATION
    }

    fun notificationText(n: Notification): String? {
        val e = n.extras ?: return null
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val body = big ?: lines ?: text ?: return null
        return "$title\n$body".trim()
    }

    /** The user allowed this app in "알림 접근" (notification access). */
    fun hasNotificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(context, PaymentNotificationListener::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }

    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun canReadInbox(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Goes through the SMS inbox for the last [days] days and books what it
     * finds. Already booked messages are skipped. Returns the lines booked.
     */
    suspend fun scanInbox(context: Context, days: Int = 30): List<String> = withContext(Dispatchers.IO) {
        if (!canReadInbox(context)) error("문자 읽기 권한이 없습니다")
        val since = System.currentTimeMillis() - days * 86_400_000L
        val booked = ArrayList<String>()
        val logbook = LogbookController.get(context)
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val body = c.getString(bodyCol) ?: continue
                logbook.onMessage(body, MessageSource.SMS, c.getLong(dateCol), force = true)?.let(booked::add)
            }
        }
        booked
    }
}
