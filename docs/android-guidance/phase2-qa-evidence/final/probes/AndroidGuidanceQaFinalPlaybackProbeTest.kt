package com.splittesla.core.qa.q1

import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.GuidanceDisplayPolicy
import com.splittesla.core.navi.GuidancePositionState
import com.splittesla.core.navi.RouteTracker
import com.splittesla.core.voice.SpeechOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Final-freeze probes (manifest 7bddc8a6…), written after reading the frozen code. Failures are findings. */
class AndroidGuidanceQaFinalPlaybackProbeTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1")
    private val route = Sim.line("우회전", 2000)

    /** Drives [steps] of (metres from the turn, km/h); every notice completes unless [drop] says otherwise. */
    private fun drive(steps: List<Pair<Double, Double>>, drop: (String) -> Boolean = { false }, legacy: Boolean = false): List<String> {
        val t = RouteTracker(route); val a = GuidanceAnnouncer()
        val heard = ArrayList<String>()
        steps.forEachIndexed { i, (d, kmh) ->
            val ms = i * 1000L
            val p = t.update(Sim.on(2000 - d), 5.0, ms)
            if (legacy) a.next(route, p, kmh)?.let { heard += "${d.toInt()}m:$it" }
            else a.nextForPlayback(route, p, kmh, ms)?.let { n ->
                if (drop(n.text)) a.playback(n, SpeechOutcome.DROPPED)
                else { a.claimStart(n); a.playback(n, SpeechOutcome.STARTED); a.playback(n, SpeechOutcome.COMPLETED); heard += "${d.toInt()}m:${n.text}" }
            }
        }
        return heard
    }
    /** About 80 km/h with [speeds] cycling, from [fromM] metres out, 22 m per second. */
    private fun approach(fromM: Double, speeds: (Double) -> Double): List<Pair<Double, Double>> =
        generateSequence(fromM) { it - 22.0 }.takeWhile { it > 8 }.map { it to speeds(it) }.toList()
    private fun lastNotices(h: List<String>) = h.filter { !it.contains("앞,") }

    /** High tier last stage heard at 150 m with the 500 m stage never heard, then slowing below 80 km/h. */
    @Test fun f7_heardLastNoticeIsNotRepeatedAfterSlowingBelowEightyKmh() {
        val steps = approach(148.0) { if (it > 100) 85.0 else 70.0 }
        val h = drive(steps)
        assertEquals(1, lastNotices(h).size, "$h")
    }

    @Test fun f7b_sameWhenTheFiveHundredMetreNoticeWasDropped() {
        val steps = approach(520.0) { if (it > 100) 85.0 else 70.0 }
        val h = drive(steps, drop = { it.startsWith("500미터") })
        assertEquals(1, lastNotices(h).size, "$h")
    }

    @Test fun f7c_speedJitterAroundEightyKmhDoesNotRepeatTheLastNotice() {
        var i = 0
        val steps = approach(760.0) { if (i++ % 2 == 0) 79.0 else 81.0 }
        val h = drive(steps)
        assertEquals(1, lastNotices(h).size, "$h")
    }

    /** 78 km/h approach, a brief 82 km/h near the turn, back to 78: an ordinary crossing of the 80 km/h tier. */
    @Test fun f7d_ordinaryApproachCrossingEightyTwiceHearsTheLastNoticeOnce() {
        val steps = approach(760.0) { if (it <= 150 && it > 80) 82.0 else 78.0 }
        val h = drive(steps)
        println("F7D new=$h legacy=" + drive(steps, legacy = true))
        assertEquals(1, lastNotices(h).size, "$h")
    }

    /** Heard at 80 km/h-tier last stage, then speeding up past 80 inside 150 m. */
    @Test fun f7e_speedingUpAfterTheLastNoticeDoesNotRepeatIt() {
        val steps = approach(760.0) { if (it <= 60) 82.0 else 78.0 }
        val h = drive(steps)
        println("F7E new=$h legacy=" + drive(steps, legacy = true))
        assertEquals(1, lastNotices(h).size, "$h")
    }

    /** Classification control: what the legacy consume-on-create path did with the same inputs. */
    @Test fun control_legacyOnF7Inputs() {
        var i = 0
        println("LEGACY f7=" + drive(approach(148.0) { if (it > 100) 85.0 else 70.0 }, legacy = true))
        println("LEGACY f7c=" + drive(approach(760.0) { if (i++ % 2 == 0) 79.0 else 81.0 }, legacy = true))
    }

    @Test fun constantSpeedsAreUntouched() {
        for (kmh in listOf(30.0, 60.0, 100.0)) {
            val h = drive(generateSequence(1100.0) { it - kmh / 3.6 }.takeWhile { it > 5 }.map { it to kmh }.toList())
            assertEquals(1, lastNotices(h).size, "$kmh $h")
        }
    }

    /** Arrival sentence cut by a final camera: not covered by Q1, measured for the report. */
    @Test fun obs_interruptedArrivalSentence() {
        val t = RouteTracker(route); val a = GuidanceAnnouncer()
        t.update(Sim.on(3498.0), 5.0, 0)
        val p = t.update(Sim.on(3499.0), 5.0, 1000)
        assertTrue(p.arrived)
        val n = a.nextForPlayback(route, p, 0.0, 1000)!!
        a.playback(n, SpeechOutcome.INTERRUPTED)
        val p2 = t.update(Sim.on(3499.0), 5.0, 2000)
        println("ARRIVAL reissue=" + a.nextForPlayback(route, p2, 0.0, 2000)?.text)
    }

    // ── Display ──
    private val now = 1_000_000L
    private fun live(active: Boolean = true, anchor: Boolean = true, tickAgo: Long = 500, fixAgo: Long = 10_000, kmh: Double? = 60.0, speedAgo: Long = 500) =
        GuidanceDisplayPolicy.estimateStillLive(active, anchor, now - tickAgo, now, now, now - fixAgo, kmh, now - speedAgo)

    @Test fun weakFixDoesNotFlickerALiveEstimateButEveryLimitEndsIt() {
        assertTrue(live())
        assertFalse(live(active = false)); assertFalse(live(anchor = false))
        assertFalse(live(tickAgo = 1501), "stale tick")
        assertFalse(live(fixAgo = 30_001), "30 s after the last good fix")
        assertFalse(live(kmh = null)); assertFalse(live(speedAgo = 3001), "stale speed")
    }

    @Test fun firstConfirmationAndHeldLabels() {
        assertEquals(GuidancePositionState.CHECKING, GuidancePositionState.resolve(false, false, false))
        assertEquals(GuidancePositionState.ESTIMATING, GuidancePositionState.resolve(false, true, true))
        assertEquals(GuidancePositionState.HELD, GuidancePositionState.resolve(false, true, false))
    }
}
