@file:Suppress("unused")
package android.speech.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import java.util.Locale

class Voice(val name: String)
abstract class UtteranceProgressListener {
    abstract fun onStart(id: String?)
    abstract fun onDone(id: String?)
    @Deprecated("Deprecated in Java") abstract fun onError(id: String?)
    open fun onError(id: String?, code: Int) = Unit
    open fun onStop(id: String?, interrupted: Boolean) = Unit
}
class TextToSpeech(@Suppress("UNUSED_PARAMETER") c: Context, val init: OnInitListener) {
    fun interface OnInitListener { fun onInit(status: Int) }
    var listener: UtteranceProgressListener? = null
    val spoken = mutableListOf<Pair<String, String>>()
    var stops = 0
    var isShutdown = false
    var language = LANG_AVAILABLE
    var speakResult = 0
    var rate = 1f
    var voice: Voice? = null
    val voices: Set<Voice>? = setOf(Voice("a"), Voice("b"))
    init { engines += this }
    fun setLanguage(l: Locale) = language
    fun setAudioAttributes(a: AudioAttributes) = 0
    fun setOnUtteranceProgressListener(l: UtteranceProgressListener) = 0.also { listener = l }
    fun setSpeechRate(r: Float) = 0.also { rate = r }
    fun speak(t: CharSequence, q: Int, p: Bundle?, id: String): Int {
        check(q == QUEUE_ADD) { "only QUEUE_ADD expected" }
        if (speakResult == 0) spoken += t.toString() to id
        return speakResult
    }
    fun stop(): Int { stops++; return 0 }
    fun shutdown() { isShutdown = true }
    companion object {
        val engines = mutableListOf<TextToSpeech>()
        const val SUCCESS = 0
        const val ERROR = -1
        const val LANG_AVAILABLE = 0
        const val LANG_MISSING_DATA = -1
        const val QUEUE_ADD = 1
    }
}
