package com.splittesla.app.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Korean text-to-speech that ducks music or a navigation app's voice while it
 * talks, and reports when it starts and stops so the microphone can pause and
 * not hear itself.
 */
class Speaker(context: Context, private val onSpeaking: (Boolean) -> Unit) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
    } else {
        null
    }
    private val pending = AtomicInteger(0)
    private var ready = false
    private var queued: String? = null
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            setup()
            queued?.let { speak(it) }
            queued = null
        }
    }

    private fun setup() {
        tts.setLanguage(Locale.KOREAN)
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onError(utteranceId: String?, errorCode: Int) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
    }

    fun speak(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) {
            queued = text
            return
        }
        if (pending.getAndIncrement() == 0) {
            focus?.let { audio.requestAudioFocus(it) }
            onSpeaking(true)
        }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "say${counter++}")
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            focus?.let { audio.abandonAudioFocusRequest(it) }
            onSpeaking(false)
        }
    }

    fun stop() {
        tts.stop()
        pending.set(0)
        focus?.let { audio.abandonAudioFocusRequest(it) }
        onSpeaking(false)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
