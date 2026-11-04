package com.splittesla.app.navi

import android.content.Context
import android.media.AudioManager
import android.os.FakeMain
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.splittesla.app.assistant.Speaker
import com.splittesla.app.assistant.VoiceChoice
import com.splittesla.core.navi.TurnSpeechContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.*

/** Exercises the same NavigationSpeech adapter called verbatim by NaviService. Fake Android only. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationDeliveryHarnessTest {
    private val leases = mutableListOf<Speaker>()
    private val ctx = Context()
    private val tts get() = TextToSpeech.engines.last()
    private fun lease(p: Int) = Speaker(ctx, priority = p).also { leases += it; FakeMain.drain() }
    private fun ready() { tts.init.onInit(0); FakeMain.drain() }
    private fun done() { tts.listener!!.onDone(tts.spoken.last().second); FakeMain.drain() }
    private fun context(guide: Int = 2) = TurnSpeechContext(1, guide, 1, 100.0, 200.0, false, SystemClock.now)
    private fun speech(c: TurnSpeechContext) = NavigationSpeech("우회전.", 70, c.createdAt + 1500, c.eventKey, c)
    @BeforeEach fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher()); FakeMain.reset(); TextToSpeech.engines.clear()
        Context.audio = AudioManager(); Context.prefs = android.content.SharedPreferences()
        VoiceChoice.set(ctx, VoiceChoice.Setting())
    }
    @AfterEach fun cleanup() { leases.forEach { it.shutdown() }; FakeMain.drain(); Dispatchers.resetMain() }
    @Test fun serviceAdapterKeepsIdenticalWordingForDifferentGuides() {
        val n = lease(70); ready()
        var current = context()
        fun deliver() { n.deliverNavigation(speech(current), true) { it.turn!!.valid(current, SystemClock.now, true) }; FakeMain.drain() }
        deliver(); done(); FakeMain.advance(500)
        current = context(3); deliver()
        assertEquals(listOf("우회전.", "우회전."), tts.spoken.map { it.first })
    }
    @Test fun finalImmediatelyCutsTurnAndLateCallbackDoesNotReplayIt() {
        val n = lease(70); val camera = lease(60); ready()
        val c = context()
        n.deliverNavigation(speech(c), true) { it.turn!!.valid(c, SystemClock.now, true) }; FakeMain.drain()
        val interrupted = tts.spoken.first().second
        camera.speak("단속 카메라", key = "final", priority = 100, ttlMs = 3000); FakeMain.drain()
        assertEquals(listOf("우회전.", "단속 카메라"), tts.spoken.map { it.first })
        tts.listener!!.onStop(interrupted, true); FakeMain.drain(); done()
        assertEquals(2, tts.spoken.size)
    }
    @Test fun waitingTurnIsRejectedByLiveProgressEvenWithinAgeBudget() {
        val n = lease(70); val camera = lease(60); ready()
        camera.speak("카메라", priority = 100); FakeMain.drain()
        val old = context(); var current = old
        n.deliverNavigation(speech(old), true) { it.turn!!.valid(current, SystemClock.now, true) }; FakeMain.drain()
        current = old.copy(alongM = 182.0, distanceM = 118.0)
        FakeMain.advance(200); done()
        assertEquals(listOf("카메라"), tts.spoken.map { it.first })
    }
    @Test fun routeReplacementInvalidatesQueuedSpeechAtEngineStart() {
        val n = lease(70); val camera = lease(60); ready()
        camera.speak("카메라", priority = 100); FakeMain.drain()
        val old = context(); var current = old
        n.deliverNavigation(speech(old), true) { it.turn!!.valid(current, SystemClock.now, true) }; FakeMain.drain()
        current = old.copy(routeVersion = 2)
        done(); assertEquals(1, tts.spoken.size)
    }
    @Test fun latestGuideEventReplacesWaitingOldStage() {
        val n = lease(70); val camera = lease(60); ready()
        camera.speak("카메라", priority = 100); FakeMain.drain()
        val old = context(); var current = old
        n.deliverNavigation(speech(old).copy(text = "오래된 거리"), true) { it.turn!!.valid(current, SystemClock.now, true) }; FakeMain.drain()
        current = old.copy(guideIndex = 3)
        n.deliverNavigation(speech(current).copy(text = "최신 턴"), true) { it.turn!!.valid(current, SystemClock.now, true) }; FakeMain.drain()
        done(); assertEquals(listOf("카메라", "최신 턴"), tts.spoken.map { it.first })
    }
    @Test fun explicitPreviewCanRepeatAndServiceMuteStillBlocks() {
        val n = lease(70); ready()
        val c = context()
        n.deliverNavigation(speech(c), false) { true }; FakeMain.drain()
        assertTrue(tts.spoken.isEmpty())
        repeat(2) {
            n.speak("샘플", key = "preview", preview = VoiceChoice.Setting()); FakeMain.drain(); done()
        }
        assertEquals(listOf("샘플", "샘플"), tts.spoken.map { it.first })
    }
    @Test fun playbackFeedbackRegeneratesACutFinalTurnOnceWithCurrentInformation() {
        val n=lease(70); val camera=lease(60); ready()
        val line=(0..20).map { com.splittesla.core.assistant.LatLon(37.5+it*.0009,127.0) }
        val route=com.splittesla.core.navi.NaviRoute(com.splittesla.core.navi.RouteOption.FAST,line,emptyList(),listOf(
            com.splittesla.core.navi.RouteGuide(10,3,"우회전",1000,72000),com.splittesla.core.navi.RouteGuide(20,88,"끝",1000,72000)),
            2000,144000,0,com.splittesla.core.navi.NaviPlace("끝",line.last()))
        val a=com.splittesla.core.navi.GuidanceAnnouncer()
        var progress=com.splittesla.core.navi.RouteProgress(975.0,1025.0,70000,0.0,false,0,25.0,null,false)
        fun send() {
            val notice=a.nextForPlayback(route,progress,30.0,SystemClock.now) ?: return
            val c=TurnSpeechContext(1,notice.guide,notice.stage,progress.alongM,progress.nextGuideDistanceM,false,SystemClock.now)
            n.deliverNavigation(NavigationSpeech(notice.text,70,SystemClock.now+1500,"${c.eventKey}:${notice.attempt}",c,canStart = { a.claimStart(notice) }) {
                a.playback(notice,it)
            },true) { it.turn!!.valid(c.copy(alongM=progress.alongM,distanceM=progress.nextGuideDistanceM),SystemClock.now,true) }
            FakeMain.drain()
        }
        send()
        camera.speak("카메라",priority=100); FakeMain.drain()
        progress=progress.copy(alongM=985.0,nextGuideDistanceM=15.0)
        send() // new, current attempt waits behind the camera
        done()
        assertEquals(listOf("우회전.","카메라","우회전."),tts.spoken.map { it.first })
        done(); send(); assertEquals(3,tts.spoken.size)
    }

}
