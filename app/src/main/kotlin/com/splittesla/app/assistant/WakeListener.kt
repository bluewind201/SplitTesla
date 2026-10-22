package com.splittesla.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class MicState(val label: String) {
    OFF("꺼짐"), LISTENING("호출어 대기"), COMMAND("명령 듣는 중"), PAUSED("말하는 중"), UNAVAILABLE("사용 불가"), NO_PERMISSION("마이크 권한 없음")
}

/**
 * Keeps Android's speech recognizer listening in a loop and hands every
 * transcript (all alternatives) to [onText]. Wake-word matching is done by
 * the caller. Must be used from the main thread.
 *
 * The phone's recognizer stops after each phrase or silence, so this restarts
 * it right away. On some phones that restart plays a short beep.
 */
class WakeListener(
    private val context: Context,
    private val onText: (List<String>) -> Unit,
    private val onState: (MicState) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var paused = false
    private var listening = false
    private var commandMode = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (wanted) return
        if (!available) {
            onState(MicState.UNAVAILABLE)
            log("음성 인식: 이 폰에서 쓸 수 있는 음성 인식기가 없습니다 (Google 앱 필요)")
            return
        }
        wanted = true
        listenSoon(0)
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        destroy()
        onState(MicState.OFF)
    }

    /** Stops listening while the assistant itself talks. */
    fun pause(on: Boolean) {
        if (paused == on) return
        paused = on
        if (!wanted) return
        if (on) {
            main.removeCallbacksAndMessages(null)
            recognizer?.cancel()
            listening = false
            onState(MicState.PAUSED)
        } else {
            listenSoon(250)
        }
    }

    /** The next phrase is a command, not a wake word check; only changes the shown state. */
    fun expectCommand(on: Boolean) {
        commandMode = on
        if (wanted && !paused) onState(if (on) MicState.COMMAND else MicState.LISTENING)
    }

    private fun listenSoon(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    private fun listen() {
        if (!wanted || paused) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        try {
            r.startListening(intent)
            listening = true
            onState(if (commandMode) MicState.COMMAND else MicState.LISTENING)
        } catch (e: Exception) {
            log("음성 인식 시작 실패: ${e.message}")
            destroy()
            listenSoon(2000)
        }
    }

    private fun destroy() {
        listening = false
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            if (texts.isNotEmpty()) onText(texts)
            listenSoon(100)
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSoon(100)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wanted = false
                    destroy()
                    onState(MicState.NO_PERMISSION)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    // A stuck recognizer only recovers when recreated.
                    destroy()
                    listenSoon(1000)
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    log("음성 인식: 네트워크 오류 ($error), 잠시 후 다시 듣습니다")
                    listenSoon(3000)
                }
                else -> {
                    destroy()
                    listenSoon(1500)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
