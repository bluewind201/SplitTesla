package com.splittesla.core.qa

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteGuide
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteTracker
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * R4b policy (PM msg_aeb41b030180): a fix vaguer than the trusted accuracy must not refresh the
 * last good anchor or extend its validity; estimated turns stay limited to the DR window that
 * started at the last good fix. Skipped unless QA_REPRO=1; never counted as a pass.
 */
class AndroidGuidanceQaR4PolicyProbeTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to run R4 policy probes")

    private val start = LatLon(37.5663, 126.9779)
    private val path = (0..40).map { LatLon(start.lat + it * 0.0009044, start.lon) }
    private val route = NaviRoute(
        option = RouteOption.FAST, path = path, sections = emptyList(),
        guides = listOf(RouteGuide(30, 3, "우회전", 3000, 200_000), RouteGuide(40, 88, "목적지", 1000, 60_000)),
        distanceM = 4000, durationMs = 260_000, tollFare = 0, goal = NaviPlace("목적지", path.last()),
    )
    private fun on(m: Double) = LatLon(start.lat + m / 110574.0, start.lon)

    /** QA-R4c: vague (50 m) on-route fixes for 25 s must not restart the 30 s estimated-turn window. */
    @Test fun r4c_vagueFixesDoNotExtendTheEstimatedTurnWindow() {
        val t = RouteTracker(route)
        val v = 60 / 3.6
        var i = 0
        while (i * v < 200) { t.update(on(i * v), 5.0, i * 1000L); i++ }
        val goodAt = i - 1
        for (k in 0 until 25) { t.update(on((i + k) * v), 50.0, (i + k) * 1000L) }
        val vagueUncertain = t.progress().positionUncertain
        // 25 s after the last good fix, then 10 s of DR: 35 s > 30 s window.
        var last = t.progress()
        repeat(10) { last = t.advance(v, 1000) }
        assertFalse(last.canAnnounceTurn,
            "35 s after the last good fix (t=${goodAt}s) DR may still announce; vague fixes left positionUncertain=$vagueUncertain")
    }

    /** QA-R4d: a vague on-route fix is not a real position: it must not clear positionUncertain. */
    @Test fun r4d_vagueOnRouteFixDoesNotBecomeACertainPosition() {
        val t = RouteTracker(route)
        val v = 60 / 3.6
        var i = 0
        while (i * v < 200) { t.update(on(i * v), 5.0, i * 1000L); i++ }
        t.update(on(i * v), 80.0, i * 1000L) // rejected → uncertain
        val p = t.update(on((i + 1) * v), 50.0, (i + 1) * 1000L)
        assertFalse(!p.positionUncertain && !p.estimated, "a 50 m fix re-certified the position: $p")
    }
}
