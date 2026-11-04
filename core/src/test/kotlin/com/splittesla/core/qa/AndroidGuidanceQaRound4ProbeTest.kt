package com.splittesla.core.qa

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteGuide
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteTracker
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Round-4 probes against manifest 53a0193a…; skipped unless QA_REPRO=1, never counted as passes. */
class AndroidGuidanceQaRound4ProbeTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to run round-4 probes")

    private val start = LatLon(37.5663, 126.9779)
    private val kx = 111320.0 * Math.cos(Math.toRadians(start.lat))
    private val path = (0..20).map { LatLon(start.lat + it * 0.0009044, start.lon) }
    private val route = NaviRoute(
        option = RouteOption.FAST, path = path, sections = emptyList(),
        guides = listOf(RouteGuide(10, 3, "우회전", 1000, 72_000), RouteGuide(20, 88, "목적지", 1000, 300_000)),
        distanceM = 2000, durationMs = 372_000, tollFare = 0, goal = NaviPlace("목적지", path.last()),
    )
    private fun on(m: Double, eastM: Double = 0.0) = LatLon(start.lat + m / 110574.0, start.lon + eastM / kx)

    private fun driveTo200(t: RouteTracker, a: GuidanceAnnouncer): Int {
        val v = 60 / 3.6
        var i = 0
        while (i * v < 200) { a.next(route, t.update(on(i * v), 5.0, i * 1000L), 60.0); i++ }
        return i
    }

    /** QA-R4b: the tunnel-mouth fix is vague (50 m) but inside FixGate reach and wanders 90 m beside the road. */
    @Test fun r4b_vagueWanderingTunnelMouthFixStillAllowsDeadReckoningTurnNotice() {
        val t = RouteTracker(route); val a = GuidanceAnnouncer(); val v = 60 / 3.6
        val i = driveTo200(t, a)
        t.update(on(i * v, eastM = 90.0), 50.0, i * 1000L)
        val spoken = ArrayList<String>()
        for (k in 1..30) a.next(route, t.advance(v, 1000), 60.0)?.let { spoken += it }
        assertNotNull(spoken.firstOrNull { it.contains("우회전") }, "spoken=$spoken last=${t.progress()}")
    }

    /** Contract: dead reckoning beyond 30 s, or with > 2 s steps, does not speak; DR never confirms arrival. */
    @Test fun r4contract_expiredOrCoarseDeadReckoningIsSilentAndNeverArrives() {
        val t = RouteTracker(route); val a = GuidanceAnnouncer(); val v = 60 / 3.6
        driveTo200(t, a)
        for (k in 1..31) t.advance(v, 1000)
        val late = t.advance(v * 10, 1000)
        assertFalse(late.canAnnounceTurn, "after 32 s of DR: $late")
        val t2 = RouteTracker(route); driveTo200(t2, GuidanceAnnouncer())
        assertFalse(t2.advance(v * 3, 3000).canAnnounceTurn, "3 s step")
        val t3 = RouteTracker(route); driveTo200(t3, GuidanceAnnouncer())
        var arrived = false
        repeat(30) { if (t3.advance(80.0, 1000).arrived) arrived = true }
        assertFalse(arrived)
        assertTrue(t3.progress().estimated)
    }
}
