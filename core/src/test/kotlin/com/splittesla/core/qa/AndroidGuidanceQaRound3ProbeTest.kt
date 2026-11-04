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
import kotlin.test.assertNotNull

/**
 * Round-3 probes against manifest 4cc88c32…. Each asserts the expected behaviour and is
 * skipped unless QA_REPRO=1; a failure here is a finding, never a pass.
 */
class AndroidGuidanceQaRound3ProbeTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to run round-3 probes")

    private val start = LatLon(37.5663, 126.9779)
    private val path = (0..20).map { LatLon(start.lat + it * 0.0009044, start.lon) }
    private val route = NaviRoute(
        option = RouteOption.FAST, path = path, sections = emptyList(),
        guides = listOf(RouteGuide(10, 3, "우회전", 1000, 72_000), RouteGuide(20, 88, "목적지", 1000, 300_000)),
        distanceM = 2000, durationMs = 372_000, tollFare = 0, goal = NaviPlace("목적지", path.last()),
    )
    private fun on(m: Double) = LatLon(start.lat + m / 110574.0, start.lon)

    /**
     * QA-R4: a tunnel mouth usually yields a vague fix (accuracy > 60 m, rejected) before the
     * signal is lost. Dead reckoning is documented to keep turn notices going in the tunnel,
     * but the rejected fix leaves positionUncertain=true and advance() never clears it.
     */
    @Test fun r4_turnNoticeStillSpokenWhileDeadReckoningAfterAVagueTunnelMouthFix() {
        val t = RouteTracker(route)
        val a = GuidanceAnnouncer()
        val v = 60 / 3.6
        var i = 0
        while (i * v < 200) { a.next(route, t.update(on(i * v), 5.0, i * 1000L), 60.0); i++ }
        t.update(on(i * v), 80.0, i * 1000L) // vague fix at the tunnel mouth: rejected
        val spoken = ArrayList<String>()
        for (k in 1..40) {
            val p = t.advance(v)
            a.next(route, p, 60.0)?.let { spoken += it }
        }
        assertNotNull(spoken.firstOrNull { it.contains("우회전") },
            "no turn notice during 40 s of dead reckoning through the turn at 1000 m; spoken=$spoken, last=${t.progress()}")
    }

    /** Control: the same tunnel without the vague mouth fix. */
    @Test fun r4control_turnNoticeSpokenWhileDeadReckoningWithoutVagueFix() {
        val t = RouteTracker(route)
        val a = GuidanceAnnouncer()
        val v = 60 / 3.6
        var i = 0
        while (i * v < 200) { a.next(route, t.update(on(i * v), 5.0, i * 1000L), 60.0); i++ }
        val spoken = ArrayList<String>()
        for (k in 1..40) a.next(route, t.advance(v), 60.0)?.let { spoken += it }
        assertNotNull(spoken.firstOrNull { it.contains("우회전") }, "spoken=$spoken")
    }
}
