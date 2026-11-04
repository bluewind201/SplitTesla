package com.splittesla.core.qa.q1

import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.RouteTracker
import com.splittesla.core.voice.SpeechOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Announcer feedback contract: outcomes, attempts and late callbacks. */
class PlaybackContractTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1")
    private val route = Sim.line("우회전", 2000)
    private fun at(metres: Double, t: RouteTracker, ms: Long) = t.update(Sim.on(metres), 5.0, ms)
    private fun lastStage(): Triple<RouteTracker, GuidanceAnnouncer, GuidanceAnnouncer.PlaybackNotice> {
        val t = RouteTracker(route); val a = GuidanceAnnouncer()
        var m = 1800.0; var ms = 0L; var n: GuidanceAnnouncer.PlaybackNotice? = null
        while (m < 1930) { // 60 km/h, last stage at 80 m
            at(m, t, ms).let { p -> a.nextForPlayback(route, p, 60.0, ms)?.let { x -> n = x; a.playback(x, SpeechOutcome.COMPLETED) } }
            m += 16.7; ms += 1000
        }
        val p = at(m, t, ms)
        val last = assertNotNull(a.nextForPlayback(route, p, 60.0, ms), "last stage notice at $m m (previous $n)")
        return Triple(t, a, last)
    }

    @Test fun completedIsNeverRepeated() {
        val (t, a, n) = lastStage()
        a.playback(n, SpeechOutcome.COMPLETED)
        var ms = 10_000L
        for (m in listOf(1945.0, 1960.0, 1975.0)) { assertNull(a.nextForPlayback(route, at(m, t, ms), 60.0, ms)); ms += 1000 }
    }

    @Test fun interruptedAndDroppedGetOneNewAttemptWhileAhead() {
        for (o in listOf(SpeechOutcome.INTERRUPTED, SpeechOutcome.DROPPED)) {
            val (t, a, n) = lastStage()
            a.playback(n, o)
            val again = assertNotNull(a.nextForPlayback(route, at(1950.0, t, 10_000), 60.0, 10_000), "$o")
            assertTrue(again.attempt > n.attempt)
            a.playback(again, SpeechOutcome.COMPLETED)
            assertNull(a.nextForPlayback(route, at(1965.0, t, 11_000), 60.0, 11_000))
        }
    }

    @Test fun acceptedOrPlayingIsNeverDuplicated() {
        for (o in listOf(SpeechOutcome.ACCEPTED, SpeechOutcome.STARTED)) {
            val (t, a, n) = lastStage()
            a.playback(n, o)
            assertNull(a.nextForPlayback(route, at(1950.0, t, 10_000), 60.0, 10_000), "$o")
            assertNull(a.nextForPlayback(route, at(1965.0, t, 11_000), 60.0, 11_000), "$o")
        }
    }

    @Test fun lateCallbackOfAnOldAttemptChangesNothing() {
        val (t, a, n) = lastStage()
        a.playback(n, SpeechOutcome.DROPPED)
        val again = assertNotNull(a.nextForPlayback(route, at(1950.0, t, 10_000), 60.0, 10_000))
        a.playback(again, SpeechOutcome.STARTED)
        a.playback(n, SpeechOutcome.COMPLETED)
        a.playback(n, SpeechOutcome.INTERRUPTED)
        assertNull(a.nextForPlayback(route, at(1965.0, t, 11_000), 60.0, 11_000), "playing attempt must not be duplicated")
        a.playback(again, SpeechOutcome.COMPLETED)
        assertNull(a.nextForPlayback(route, at(1975.0, t, 12_000), 60.0, 12_000))
    }

    @Test fun lateCallbackFromAnOldSessionCannotOweTheNewRoute() {
        val (_, a, n) = lastStage()
        a.reset()
        a.playback(n, SpeechOutcome.INTERRUPTED)
        val fresh = RouteTracker(route)
        assertNull(a.nextForPlayback(route, at(1100.0, fresh, 20_000), 60.0, 20_000), "nothing is due 900 m out")
        fresh.update(Sim.on(1110.0), 5.0, 21_000)
        val p = fresh.update(Sim.on(1120.0), 5.0, 22_000)
        assertNull(a.nextForPlayback(route, p, 60.0, 22_000))
    }

    @Test fun nothingOnceTheTurnIsPassedOrPositionIsUncertain() {
        val (t, a, n) = lastStage()
        a.playback(n, SpeechOutcome.INTERRUPTED)
        assertNull(a.nextForPlayback(route, t.update(Sim.on(1950.0), 50.0, 10_000), 60.0, 10_000), "uncertain")
        val (t2, a2, n2) = lastStage()
        a2.playback(n2, SpeechOutcome.INTERRUPTED)
        at(1970.0, t2, 8_000); at(1990.0, t2, 9_000); at(2010.0, t2, 10_000)
        val p = at(2030.0, t2, 11_000)
        val x = a2.nextForPlayback(route, p, 60.0, 11_000)
        assertFalse(x?.text?.contains("우회전") == true, "turn already passed: ${x?.text}")
    }

    @Test fun waitingWorkIsRefreshedNotDuplicated() {
        val (t, a, n) = lastStage()
        val refreshed = a.nextForPlayback(route, at(1950.0, t, 10_000), 60.0, 10_000)
        if (refreshed != null) assertTrue(refreshed.attempt > n.attempt)
        a.playback(n, SpeechOutcome.DROPPED)
        assertEquals(1, listOfNotNull(refreshed).size, "old attempt's late drop must not owe a second notice")
        refreshed?.let { a.playback(it, SpeechOutcome.COMPLETED) }
        assertNull(a.nextForPlayback(route, at(1965.0, t, 11_000), 60.0, 11_000))
    }
}
