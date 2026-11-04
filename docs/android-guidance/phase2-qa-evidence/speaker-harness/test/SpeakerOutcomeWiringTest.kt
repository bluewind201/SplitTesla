package com.splittesla.app.assistant

import android.content.Context
import android.media.AudioManager
import android.os.FakeMain
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.splittesla.app.navi.NavigationSpeech
import com.splittesla.app.navi.deliverNavigation
import com.splittesla.core.voice.SpeechOutcome
import com.splittesla.core.voice.SpeechOutcome.ACCEPTED
import com.splittesla.core.voice.SpeechOutcome.COMPLETED
import com.splittesla.core.voice.SpeechOutcome.DROPPED
import com.splittesla.core.voice.SpeechOutcome.INTERRUPTED
import com.splittesla.core.voice.SpeechOutcome.STARTED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every delivered turn sentence must report exactly one terminal outcome on every path. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerOutcomeWiringTest {
    private val ctx = Context()
    private val open = mutableListOf<Speaker>()
    private fun lease(p: Int = 70) = Speaker(ctx, priority = p).also { open += it; FakeMain.drain() }
    private val tts get() = TextToSpeech.engines.last()
    private fun ready() { FakeMain.drain(); tts.init.onInit(0); FakeMain.drain() }
    private fun id(i: Int = 0) = tts.spoken[i].second
    private val log = mutableListOf<SpeechOutcome>()
    private val terminal get() = log.filter { it == COMPLETED || it == DROPPED || it == INTERRUPTED }
    private fun turn(s: Speaker, valid: Boolean = true, enabled: Boolean = true, ttl: Long = 1500, key: String = "turn:1:0:2:attempt:1") {
        s.deliverNavigation(NavigationSpeech("우회전.", 70, SystemClock.now + ttl, key, null) { log += it }, enabled) { valid }
        FakeMain.drain()
    }

    @BeforeEach fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        FakeMain.reset(); TextToSpeech.engines.clear(); log.clear()
        Context.audio = AudioManager(); Context.prefs = android.content.SharedPreferences()
        VoiceChoice.set(ctx, VoiceChoice.Setting())
    }
    @AfterEach fun tearDown() { open.forEach { it.shutdown() }; open.clear(); FakeMain.drain(); Dispatchers.resetMain() }

    @Test fun completedReportsOnceAndLateCallbackAddsNothing() {
        val s = lease(); ready(); turn(s)
        val u = id()
        tts.listener!!.onStart(u); FakeMain.drain()
        tts.listener!!.onDone(u); FakeMain.drain()
        tts.listener!!.onDone(u); tts.listener!!.onStop(u, true); FakeMain.drain()
        assertEquals(listOf(COMPLETED), terminal, "$log")
        assertTrue(log.containsAll(listOf(ACCEPTED, STARTED)) || log.contains(STARTED), "$log")
    }

    @Test fun cutByFinalReportsInterruptedOnce() {
        val s = lease(); val c = lease(60); ready(); turn(s)
        val u = id()
        c.speak("최종", key = "f", priority = 100, ttlMs = 3000); FakeMain.drain()
        tts.listener!!.onStop(u, true); FakeMain.drain()
        assertEquals(listOf(INTERRUPTED), terminal, "$log")
    }

    @Test fun invalidAtStartAndDisabledAndExpiredOnArrivalReportDropped() {
        val s = lease(); ready()
        turn(s, valid = false); assertEquals(listOf(DROPPED), terminal, "$log"); log.clear()
        turn(s, enabled = false, key = "k2"); assertEquals(listOf(DROPPED), terminal, "$log"); log.clear()
        turn(s, ttl = 0, key = "k3"); assertEquals(listOf(DROPPED), terminal, "$log")
    }

    @Test fun expiringWhileWaitingReportsDropped() {
        val s = lease(); val c = lease(60); ready()
        c.speak("최종", key = "f", priority = 100, ttlMs = 3000); FakeMain.drain()
        turn(s)
        SystemClock.now += 2000
        tts.listener!!.onDone(id()); FakeMain.drain()
        assertEquals(listOf(DROPPED), terminal, "a sentence that silently expires can never be re-offered: $log")
    }

    @Test fun obs_expiryIsReportedWithoutWaitingForTheNextQueueActivity() {
        val s = lease(); val c = lease(60); ready()
        c.speak("긴 최종", key = "f", priority = 100, ttlMs = 3000); FakeMain.drain()
        turn(s)
        FakeMain.advance(2000)
        assertEquals(listOf(DROPPED), terminal, "no pump happened while the engine kept talking: $log")
    }

    @Test fun duplicateKeyIsReportedNotLost() {
        val s = lease(); ready(); turn(s); turn(s)
        assertEquals(listOf(DROPPED), terminal, "$log")
    }

    @Test fun muteOwnerStopAndShutdownReportOnce() {
        val s = lease(); ready(); turn(s); turn(s, key = "w")
        VoiceChoice.set(ctx, VoiceChoice.Setting(muted = true)); FakeMain.drain()
        assertEquals(2, terminal.size, "$log"); log.clear()
        VoiceChoice.set(ctx, VoiceChoice.Setting()); FakeMain.drain()
        turn(s, key = "a"); s.stop(); FakeMain.drain()
        assertEquals(1, terminal.size, "$log"); log.clear()
        turn(s, key = "b"); s.shutdown(); FakeMain.drain()
        assertEquals(1, terminal.size, "$log")
    }

    @Test fun whileMutedDeliveryReportsDropped() {
        val s = lease(); ready()
        VoiceChoice.set(ctx, VoiceChoice.Setting(muted = true)); FakeMain.drain()
        turn(s)
        assertEquals(listOf(DROPPED), terminal, "$log")
    }

    @Test fun focusDeniedFocusLostEngineErrorAndWatchdogEachReportOnce() {
        val s = lease(); ready()
        Context.audio.grant = false; turn(s, key = "a"); FakeMain.advance(300)
        assertEquals(1, terminal.size, "denied $log"); log.clear()
        Context.audio.grant = true; turn(s, key = "b")
        Context.audio.last!!.listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT); FakeMain.advance(600)
        assertEquals(listOf(INTERRUPTED), terminal, "lost $log"); log.clear()
        tts.speakResult = TextToSpeech.ERROR; turn(s, key = "c"); tts.speakResult = 0
        assertEquals(1, terminal.size, "error $log"); log.clear()
        turn(s, key = "d"); FakeMain.advance(30_000)
        assertEquals(1, terminal.size, "watchdog $log")
    }
}
