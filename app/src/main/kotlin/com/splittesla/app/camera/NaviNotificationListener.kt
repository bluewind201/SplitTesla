package com.splittesla.app.camera

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.splittesla.core.camera.NaviHint
import com.splittesla.core.camera.NaviText
import kotlinx.coroutines.flow.MutableStateFlow

/** The latest guidance a phone navigation app showed in its notification. */
data class NaviFeed(val app: String, val lines: List<String>, val hint: NaviHint?, val at: Long)

/**
 * Reads the ongoing notification Naver Map, TMAP or Kakao Navi keep while
 * guiding in the background, so their speed camera and turn info can sit in
 * this app's camera panel. Needs the user to allow notification access.
 *
 * Their floating window over other apps cannot be read by another app, but
 * the same guidance is usually in the notification.
 */
class NaviNotificationListener : NotificationListenerService() {
    private var lastLogAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        // Guidance lives in the ongoing notification; skip ads and messages from the same app.
        if (!sbn.isOngoing) return
        val lines = textOf(sbn.notification)
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        val hint = NaviText.parse(lines)
        val previous = feed.value
        feed.value = NaviFeed(app, lines, hint, now)
        // The text changes every second during guidance; log a sample, and every camera change.
        if (hint?.cameraKind != previous?.hint?.cameraKind || now - lastLogAt > 20_000) {
            lastLogAt = now
            lastRaw.value = "$app: ${lines.joinToString(" | ")}"
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Guidance ended: its ongoing notification went away.
        if (sbn.isOngoing && feed.value?.app == APPS[sbn.packageName]) feed.value = null
    }

    /** Standard text fields first, then any text drawn by a custom layout. */
    private fun textOf(n: Notification): List<String> {
        val e = n.extras
        val out = mutableListOf<String>()
        listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT,
        ).forEach { key -> e.getCharSequence(key)?.toString()?.let(out::add) }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { out += it.toString() }
        @Suppress("DEPRECATION")
        listOfNotNull(n.bigContentView, n.contentView).forEach { out += textOf(it) }
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun textOf(views: RemoteViews): List<String> = try {
        val root = views.apply(applicationContext, FrameLayout(applicationContext))
        buildList { collect(root, this) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun collect(v: View, out: MutableList<String>) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView) v.text?.toString()?.let(out::add)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    companion object {
        private val APPS = mapOf(
            "com.nhn.android.nmap" to "네이버 지도",
            "com.skt.tmap.ku" to "TMAP",
            "com.skt.skaf.l001mtm091" to "TMAP",
            "com.locnall.KimGiSa" to "카카오내비",
            "net.daum.android.map" to "카카오맵",
        )

        val feed = MutableStateFlow<NaviFeed?>(null)

        /** The last new text seen, for the log while we learn each app's wording. */
        val lastRaw = MutableStateFlow<String?>(null)

        fun allowed(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NaviNotificationListener::class.java).flattenToString()
            return enabled.split(':').any { it == me }
        }
    }
}
