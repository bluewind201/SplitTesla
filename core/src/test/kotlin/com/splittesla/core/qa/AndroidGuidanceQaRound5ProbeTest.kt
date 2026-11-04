package com.splittesla.core.qa

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteGuide
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteTracker
import com.splittesla.core.navi.SpeedEvidence
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-5 probes against manifest 572dce57…: independent checks of the weak-fix policy
 * (PM msg_aeb41b030180). Skipped unless QA_REPRO=1. The l2_* tests pin a policy consequence
 * and measure it; passing them is not evidence that the behaviour is desirable.
 */
class AndroidGuidanceQaRound5ProbeTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to run round-5 probes")

    private val start = LatLon(37.5663, 126.9779)
    private val kx = 111320.0 * Math.cos(Math.toRadians(start.lat))
    private val path = (0..40).map { LatLon(start.lat + it * 0.0009044, start.lon) }
    private val route = NaviRoute(
        option = RouteOption.FAST, path = path, sections = emptyList(),
        guides = listOf(RouteGuide(15, 3, "우회전", 1500, 100_000), RouteGuide(40, 88, "목적지", 2500, 160_000)),
        distanceM = 4000, durationMs = 260_000, tollFare = 0, goal = NaviPlace("목적지", path.last()),
    )
    private val v = 60 / 3.6
    private fun on(m: Double, eastM: Double = 0.0) = LatLon(start.lat + m / 110574.0, start.lon + eastM / kx)

    /** Good 1 Hz fixes up to about 200 m; returns the next second. */
    private fun seed(t: RouteTracker, a: GuidanceAnnouncer? = null): Int {
        var i = 0
        while (i * v < 200) { val p = t.update(on(i * v), 5.0, i * 1000L); a?.next(route, p, 60.0); i++ }
        return i
    }

    @Test fun goodAccuracyContradictionStillCancelsTheAnchor() {
        val t = RouteTracker(route)
        val i = seed(t)
        val p = t.update(on(i * v, eastM = 90.0), 20.0, i * 1000L)
        assertTrue(p.positionUncertain)
        assertFalse(t.advance(v, 1000).canAnnounceTurn, "accurate off-route fix must block estimated turns")
    }

    @Test fun repeatedGoodOffRouteStillReachesOffRoute() {
        val t = RouteTracker(route)
        val i = seed(t)
        val seen = (0..2).map { k -> t.update(on((i + k) * v, eastM = 90.0), 20.0, (i + k) * 1000L).offRoute }
        assertEquals(listOf(false, false, true), seen)
    }

    @Test fun repeatedWeakOffRouteNeverCountsAsLeavingTheRoute() {
        val t = RouteTracker(route)
        val i = seed(t)
        for (k in 0..20) assertFalse(t.update(on((i + k) * v, eastM = 90.0), 50.0, (i + k) * 1000L).offRoute)
    }

    @Test fun trustBoundaryIsThirtyMetresInclusive() {
        val t = RouteTracker(route)
        val i = seed(t)
        val before = t.progress().alongM
        val weak = t.update(on(i * v), 30.01, i * 1000L)
        assertEquals(before, weak.alongM, 0.001)
        assertTrue(weak.positionUncertain)
        val good = t.update(on((i + 1) * v), 30.0, (i + 1) * 1000L)
        assertFalse(good.positionUncertain)
        assertTrue(good.alongM > before)
    }

    @Test fun weakFixesFarAheadMoveNothingAndDoNotPoisonTheMotionGate() {
        val t = RouteTracker(route)
        val i = seed(t)
        val before = t.progress().alongM
        for (k in 0..3) {
            val p = t.update(on((i + k) * v + 300), 50.0, (i + k) * 1000L)
            assertEquals(before, p.alongM, 0.001, "weak fix moved progress")
            assertFalse(p.arrived)
        }
        // The very next good fix continues the real track at once: no candidate or frozen reach left behind.
        val p = t.update(on((i + 4) * v), 5.0, (i + 4) * 1000L)
        assertFalse(p.positionUncertain, "$p")
        assertEquals((i + 4) * v, p.alongM, 3.0)
    }

    @Test fun weakFixesAtTheDestinationNeverConfirmArrival() {
        val t = RouteTracker(route)
        var i = 0
        while (i * v < 3990) { t.update(on(i * v), 5.0, i * 1000L); i++ }
        for (k in 0..10) assertFalse(t.update(on(3998.0), 35.0, (i + k) * 1000L).arrived)
        // An 11 s weak stretch is a real outage to the motion gate: it reacquires first, then needs two
        // confirmations. The first draft expected two good fixes in total, which was a wrong QA expectation.
        assertFalse(t.update(on(3998.0), 5.0, (i + 11) * 1000L).arrived, "one good fix after weak ones")
        var goodFixes: Int? = null
        for (k in 12..30) if (goodFixes == null && t.update(on(3998.0), 5.0, (i + k) * 1000L).arrived) goodFixes = k - 10
        println("ARRIVAL good fixes after weak stretch=$goodFixes")
        assertTrue((goodFixes ?: 99) in 2..8, "arrival took $goodFixes good fixes")
    }

    @Test fun estimatedWindowEndsThirtySecondsAfterTheLastGoodFixWhateverWeakFixesSay() {
        fun afterWeak(seconds: Int): Boolean {
            val t = RouteTracker(route)
            val i = seed(t)
            for (k in 0 until seconds) t.update(on((i + k) * v), 50.0, (i - 1 + k + 1) * 1000L)
            return t.advance(v, 1000).canAnnounceTurn
        }
        assertTrue(afterWeak(20), "20 s of weak fixes + 1 s DR is inside the window")
        assertFalse(afterWeak(30), "30 s of weak fixes + 1 s DR is past the window")
    }

    @Test fun oneGoodFixAfterExpiryIsNeededBeforeEstimatesMayBeSpokenAgain() {
        val t = RouteTracker(route)
        val i = seed(t)
        repeat(31) { t.advance(v, 1000) }
        assertFalse(t.advance(v, 1000).canAnnounceTurn)
        var k = 0
        var restored = false
        while (k < 15 && !restored) {
            restored = !t.update(on((i + 33 + k) * v), 5.0, (i + 33 + k) * 1000L).positionUncertain
            k++
        }
        assertTrue(restored, "good fixes did not restore a real position")
        assertTrue(t.advance(v, 1000).canAnnounceTurn)
    }

    @Test fun sampleTimeUsesAgeAndRejectsOutOfRangeSamples() {
        assertEquals(97_100L, SpeedEvidence.fixSampleAtMs(100_000, 2_900_000_000L))
        assertEquals(97_000L, SpeedEvidence.fixSampleAtMs(100_000, 3_000_000_000L))
        assertNull(SpeedEvidence.fixSampleAtMs(100_000, 3_000_000_001L))
        assertNull(SpeedEvidence.fixSampleAtMs(100_000, -1))
        assertNull(SpeedEvidence.fixSampleAtMs(0, 0))
        // A 2.9 s old sample received now leaves only 0.1 s before dead reckoning may begin.
        val at = SpeedEvidence.fixSampleAtMs(100_000, 2_900_000_000L)!!
        assertNotNull(SpeedEvidence.deadReckoningKmh(100_102, at, 60.0, 100_100))
        assertNull(SpeedEvidence.deadReckoningKmh(at + 30_001, at, 60.0, at + 30_001))
    }

    /** QA-L2 pin: 60 s of accurate-looking 40 m fixes, no vehicle speed: nothing moves and nothing is said. */
    @Test fun l2_sustainedWeakGpsWithoutVehicleSpeedFreezesGuidance() {
        val t = RouteTracker(route); val a = GuidanceAnnouncer()
        val i = seed(t, a)
        val before = t.progress().alongM
        val spoken = ArrayList<String>()
        for (k in 0 until 60) {
            val p = t.update(on((i + k) * v), 40.0, (i + k) * 1000L)
            a.next(route, p, 60.0)?.let { spoken += it }
            assertFalse(p.offRoute)
        }
        assertEquals(before, t.progress().alongM, 0.001)
        assertTrue(spoken.isEmpty(), "$spoken")
        // Recovery once accuracy returns.
        var firstOk: Int? = null
        for (k in 60..80) {
            val p = t.update(on((i + k) * v), 5.0, (i + k) * 1000L)
            if (firstOk == null && !p.positionUncertain) firstOk = k - 60
        }
        println("L2 recovery fixes=$firstOk")
        assertTrue((firstOk ?: 99) <= 6, "recovery took $firstOk good fixes")
    }

    /** QA-L2 pin: with vehicle speed the same stretch keeps estimated notices for 30 s, then goes silent. */
    @Test fun l2_sustainedWeakGpsWithVehicleSpeedGoesSilentAfterThirtySeconds() {
        val t = RouteTracker(route); val a = GuidanceAnnouncer()
        val i = seed(t, a)
        var lastAllowed = -1
        for (k in 0 until 60) {
            t.update(on((i + k) * v), 40.0, (i + k) * 1000L)
            if (k < 3) continue // NaviHub starts dead reckoning 3 s after the last good sample
            val p = t.advance(v, 1000)
            if (p.canAnnounceTurn) lastAllowed = k
        }
        println("L2 last estimated-turn second=$lastAllowed")
        assertTrue(lastAllowed in 20..30, "lastAllowed=$lastAllowed")
    }
}
