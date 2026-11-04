package com.splittesla.app.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.splittesla.core.voice.SpeechQueue
import com.splittesla.core.voice.SpeechOutcome
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** A lease on the process-wide engine. Closing a lease only cancels its own speech. */
class Speaker(context: Context, private val log: (String) -> Unit = {}, private val priority: Int = 10, enabled: () -> Boolean = { true }) {
    private val owner = ids.incrementAndGet()
    private var closed = false
    private val engine: SharedSpeech
    init {
        VoiceChoice.load(context)
        synchronized(Speaker::class.java) {
            engine = shared ?: SharedSpeech(context.applicationContext).also { shared = it }
            engine.acquire(owner, log, enabled)
        }
    }
    fun speak(text: String, interrupt: Boolean = false, key: String? = null,
              priority: Int = this.priority, ttlMs: Long = 8000, group: String? = null, preview: VoiceChoice.Setting? = null, validAtStart: (() -> Boolean)? = null, onOutcome: (SpeechOutcome) -> Unit = {}) {
        val createdAt = SystemClock.elapsedRealtime()
        val eventKey = if (preview != null || (key == null && priority == 70)) "event:${ids.incrementAndGet()}" else key ?: text
        val validate = validAtStart ?: { priority != 70 || SystemClock.elapsedRealtime() - createdAt <= 1000 }
        if (!closed) engine.submit(SpeechQueue.Request(owner, text, eventKey, priority,
            createdAt + ttlMs, group, preview?.voiceName, preview?.rate,
            deduplicate = preview == null && (priority != 70 || key != null), validAtStart = validate, onOutcome = onOutcome), interrupt)
        else onOutcome(SpeechOutcome.DROPPED)
    }
    fun stop() { if (!closed) engine.cancel(owner) }
    fun shutdown() {
        synchronized(Speaker::class.java) {
            if (closed) return
            closed = true
            if (engine.release(owner)) shared = null
        }
    }
    companion object {
        private val ids = AtomicLong()
        private var shared: SharedSpeech? = null
    }
}

/** All engine and policy mutations run on the main looper, including TTS callbacks. */
private class SharedSpeech(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val owners = mutableMapOf<Long, (String) -> Unit>()
    private val enabledOwners = mutableMapOf<Long, () -> Boolean>()
    private val leases = mutableSetOf<Long>()
    private val queue = SpeechQueue()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                // A lost sentence is never replayed later with stale distance information.
                stopActive()
                handler.postDelayed({ pump() }, 500)
            }
        }, handler).build()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var disposed = false
    private var token = 0L
    private var activeId: String? = null
    private var applied: VoiceChoice.Setting? = null
    private val muteJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).let { scope ->
        scope.launchMuteWatch()
    }
    private fun kotlinx.coroutines.CoroutineScope.launchMuteWatch() = launch {
        VoiceChoice.setting.collect { if (it.muted) { queue.clear(); stopActive() } }
    }
    init {
        handler.post {
            tts = TextToSpeech(context) { status -> handler.post {
                if (disposed) return@post
                ready = status == TextToSpeech.SUCCESS
                if (!ready) report("음성 엔진을 시작하지 못했습니다 (코드 $status)")
                else {
                    val e = tts ?: return@post
                    if (e.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
                        ready = false
                        report("한국어 음성 데이터가 없어 음성 안내를 보류합니다")
                    }
                    e.setAudioAttributes(attributes)
                    e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { handler.post { if (id != null && id == activeId) queue.active?.onOutcome?.invoke(SpeechOutcome.STARTED) } }
                        override fun onDone(id: String?) = complete(id)
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) = complete(id, SpeechOutcome.DROPPED)
                        override fun onError(id: String?, code: Int) = complete(id, SpeechOutcome.DROPPED)
                        override fun onStop(id: String?, interrupted: Boolean) = complete(id, SpeechOutcome.INTERRUPTED)
                    })
                    pump()
                }
            } }
        }
    }
    @Synchronized fun acquire(owner: Long, log: (String) -> Unit, enabled: () -> Boolean) {
        leases += owner
        handler.post { if (!disposed) { owners[owner] = log; enabledOwners[owner] = enabled } }
    }
    @Synchronized fun release(owner: Long): Boolean {
        leases -= owner
        val last = leases.isEmpty()
        handler.post {
            cancelNow(owner)
            owners.remove(owner)
            enabledOwners.remove(owner)
            if (last) {
                disposed = true
                muteJob.cancel()
                queue.clear()
                stopActive()
                tts?.shutdown()
                tts = null
            }
        }
        return last
    }
    fun submit(request: SpeechQueue.Request, interrupt: Boolean) { handler.post {
        if (disposed || request.owner !in owners || VoiceChoice.setting.value.muted) { request.onOutcome(SpeechOutcome.DROPPED); return@post }
        // Interrupt means replace this owner's waiting work; it never cuts another owner.
        // Final camera guidance may interrupt a turn; the interrupted sentence is discarded.
        // Its old text is never blindly requeued. New turn events carry live context validation.
        if (interrupt && queue.active?.owner != request.owner) queue.cancel(request.owner)
        if (queue.offer(request, SystemClock.elapsedRealtime()) && queue.shouldPreempt(request)) stopActive()
        pump()
    } }
    fun cancel(owner: Long) { handler.post { cancelNow(owner) } }
    private fun cancelNow(owner: Long) {
        if (queue.cancel(owner)) stopActive()
        pump()
    }
    private fun report(message: String) { owners.values.forEach { it(message) } }
    private fun stopActive() {
        activeId = null
        queue.finish(SpeechOutcome.INTERRUPTED)
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
    }
    private fun complete(id: String?, outcome: SpeechOutcome = SpeechOutcome.COMPLETED) { handler.post {
        // Late stop/error/done callbacks from a replaced utterance cannot finish the new one.
        if (id == null || id != activeId) return@post
        activeId = null
        queue.finish(outcome)
        audio.abandonAudioFocusRequest(focus)
        pump()
    } }
    private fun pump() {
        if (disposed || !ready || activeId != null || VoiceChoice.setting.value.muted) return
        val next = queue.next(SystemClock.elapsedRealtime()) ?: return
        if (enabledOwners[next.owner]?.invoke() != true) { queue.finish(SpeechOutcome.DROPPED); pump(); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            queue.finish(SpeechOutcome.DROPPED)
            report("오디오 사용 권한을 얻지 못해 음성 안내를 건너뜁니다")
            handler.postDelayed({ pump() }, 250)
            return
        }
        val e = tts ?: return
        val saved = VoiceChoice.setting.value
        val previewRate = next.rate
        val want = if (previewRate != null) saved.copy(voiceName = next.voiceName, rate = previewRate) else saved
        if (want != applied) {
            val voice = want.voiceName?.let { name -> runCatching { e.voices }.getOrNull()?.firstOrNull { it.name == name } }
            if (voice != null) e.voice = voice else e.setLanguage(Locale.KOREAN)
            e.setSpeechRate(want.rate)
            applied = want
        }
        if (!next.validAtStart() || next.expiresAt <= SystemClock.elapsedRealtime()) {
            queue.finish(SpeechOutcome.DROPPED)
            audio.abandonAudioFocusRequest(focus)
            pump()
            return
        }
        val id = "shared${++token}"
        activeId = id
        if (e.speak(next.text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) complete(id, SpeechOutcome.DROPPED)
        else next.onOutcome(SpeechOutcome.ACCEPTED)
        // Broken engines cannot hold the shared queue and focus forever.
        handler.postDelayed({ if (activeId == id) { stopActive(); pump() } }, 30000)
    }
}
