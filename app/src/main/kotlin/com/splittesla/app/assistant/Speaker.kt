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
 * talks.
 */
class Speaker(context: Context, private val log: (String) -> Unit = {}) {
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
        if (!ready) log("음성 엔진(TTS)을 시작하지 못해 음성 안내를 할 수 없습니다 (코드 $status)")
        if (ready) {
            setup()
            queued?.let { speak(it) }
            queued = null
        }
    }

    private fun setup() {
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang < TextToSpeech.LANG_AVAILABLE) {
            log("한국어 음성 데이터가 없습니다. 휴대폰 설정 > 텍스트 음성 변환에서 한국어를 설치하세요")
        }
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
        }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "say${counter++}")
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            focus?.let { audio.abandonAudioFocusRequest(it) }
        }
    }

    fun stop() {
        tts.stop()
        pending.set(0)
        focus?.let { audio.abandonAudioFocusRequest(it) }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
