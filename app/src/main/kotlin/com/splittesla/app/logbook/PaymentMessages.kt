package com.splittesla.app.logbook

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.splittesla.core.logbook.MessageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sideload-safe payment message ingestion (no `READ_SMS`, `RECEIVE_SMS`, or
 * `BIND_NOTIFICATION_LISTENER_SERVICE`, which Google Play Protect Enhanced
 * Fraud Protection blocks on APKs installed outside the Play Store):
 *
 * 1. Clipboard auto-recognition on app focus / 1-tap button
 * 2. Android Share sheet (`Intent.ACTION_SEND` from KakaoTalk / Messages)
 * 3. Exported chat / message file import (`.txt` / `.csv`)
 * 4. Deep link (`splittesla://pay?text=...`) for Samsung Modes & Routines
 */
object PaymentMessages {
    /** Reads plain text from the clipboard, or null when empty. */
    fun readClipboard(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Reads a shared or imported text file URI (e.g. KakaoTalk exported `.txt`). */
    suspend fun readTextUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("파일을 읽을 수 없습니다")
    }

    /**
     * Extracts payment text from an incoming `ACTION_SEND` share intent or
     * `splittesla://pay?text=...` routine deep link.
     */
    suspend fun extractIncoming(context: Context, intent: Intent?): Pair<String, MessageSource>? {
        val i = intent ?: return null
        when (i.action) {
            Intent.ACTION_SEND -> {
                val inline = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                val streamText = if (streamUri != null) runCatching { readTextUri(context, streamUri) }.getOrDefault("") else ""
                val combined = listOf(inline, streamText).filter { it.isNotBlank() }.joinToString("\n\n").trim()
                if (combined.isEmpty()) return null
                val src = if (combined.contains("카카오") || combined.contains("KakaoTalk", ignoreCase = true)) {
                    MessageSource.KAKAO
                } else {
                    MessageSource.SMS
                }
                return combined to src
            }
            Intent.ACTION_VIEW -> {
                val data = i.data ?: return null
                if (data.scheme != "splittesla") return null
                val text = (data.getQueryParameter("text") ?: data.getQueryParameter("msg") ?: data.fragment)?.trim().orEmpty()
                if (text.isEmpty()) return null
                return text to MessageSource.NOTIFICATION
            }
            else -> return null
        }
    }
}
