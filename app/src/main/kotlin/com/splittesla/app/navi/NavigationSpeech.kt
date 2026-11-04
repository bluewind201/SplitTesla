package com.splittesla.app.navi

import android.os.SystemClock
import com.splittesla.app.assistant.Speaker
import com.splittesla.core.navi.TurnSpeechContext

/** The service carries the event identity and live validation all the way into the shared queue. */
data class NavigationSpeech(val text: String, val priority: Int, val expiresAt: Long,
                            val key: String, val turn: TurnSpeechContext? = null,
                            val canStart: () -> Boolean = { true },
                            val onOutcome: (com.splittesla.core.voice.SpeechOutcome) -> Unit = {})

fun Speaker.deliverNavigation(speech: NavigationSpeech, enabled: Boolean,
                              validate: (NavigationSpeech) -> Boolean) {
    val remaining = speech.expiresAt - SystemClock.elapsedRealtime()
    if (!enabled || remaining <= 0) speech.onOutcome(com.splittesla.core.voice.SpeechOutcome.DROPPED)
    if (enabled && remaining > 0) speak(speech.text, key = speech.key, priority = speech.priority,
        ttlMs = remaining, group = if (speech.turn != null) "navigation-turn" else null,
        validAtStart = { validate(speech) && speech.canStart() }, onOutcome = speech.onOutcome)
}
