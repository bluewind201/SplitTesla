package com.splittesla.core.qa

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.FixGate
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteTracker
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Final-round probes written against the frozen fix (manifest 6c2940d6…). Like
 * [AndroidGuidanceQaDefectsTest], each asserts the expected behaviour, fails when the
 * residual exists, and is skipped unless QA_REPRO=1. Never counted as a pass.
 */
class AndroidGuidanceQaFinalProbeTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to run final probes")

    private fun north(m: Double) = LatLon(37.5 + m / 110574.0, 127.0)

    /** QA-R1: D1 class beyond the new 8 s window: fixes 10 s apart. */
    @Test fun r1_fixesTenSecondsApartAreNotLockedOutForever() {
        val g = FixGate()
        val v = 60 / 3.6
        var accepted = 0
        for (i in 0..18) if (g.accept(north(i * 10 * v), i * 10_000L, 5.0) != null) accepted++
        assertTrue(accepted > 6, "accepted only $accepted of 19 fixes over 3 minutes")
    }

    /** QA-R2: D8 when the bad first fix claims good accuracy (5 m), so the bootstrap shortcut does not apply. */
    @Test fun r2_badFirstFixWithGoodClaimedAccuracyRecoversWithinThreeSeconds() {
        val g = FixGate()
        g.accept(north(500.0), 0, 5.0)
        val v = 60 / 3.6
        var firstOk: Int? = null
        for (i in 1..20) if (g.accept(north(i * v), i * 1000L, 5.0) != null && firstOk == null) firstOk = i
        assertTrue((firstOk ?: 99) <= 3, "first real fix accepted after $firstOk s")
    }

    /** QA-R3: hairpin (two legs 30 m apart), 20 s GPS gap with no vehicle speed (no DR). */
    @Test fun r3_hairpinAfterGapWithoutDeadReckoningRecoversQuickly() {
        val start = LatLon(37.5663, 126.9779)
        val kx = 111320.0 * Math.cos(Math.toRadians(start.lat))
        val up = (0..100).map { LatLon(start.lat + it * 10 / 110574.0, start.lon) } // 1000 m north
        val down = (0..100).map { LatLon(up.last().lat - it * 10 / 110574.0, start.lon + 30 / kx) } // 1000 m south, 30 m east
        val path = up + down
        val r = NaviRoute(RouteOption.FAST, path, emptyList(), emptyList(), 0, 0, 0, NaviPlace("x", path.last()))
        val t = RouteTracker(r)
        val v = 50 / 3.6
        var i = 0
        while (i * v < 950) { t.update(LatLon(start.lat + i * v / 110574.0, start.lon), 5.0, i * 1000L); i++ }
        // 20 s gap: car drives 950 → 1000 north, across 30 m, and down the return leg. Fixes resume at along ≈ 1130.
        var firstOk: Int? = null
        var minAlongAfterResume = Double.MAX_VALUE
        val resumeAt = (i + 20) * 1000L
        for (k in 0..30) {
            val alongTrue = 1130 + k * v
            val southM = alongTrue - 1030
            val p = t.update(LatLon(up.last().lat - southM / 110574.0, start.lon + 30 / kx), 5.0, resumeAt + k * 1000)
            if (firstOk == null && kotlin.math.abs(p.alongM - alongTrue) < 40) firstOk = k
            minAlongAfterResume = minOf(minAlongAfterResume, p.alongM)
        }
        // Observed on the frozen fix: snaps to the outbound leg (lateral 30 m, inside the off-route limit)
        // and then slides backwards (944 → 483 m over 30 fixes) while the car drives forward.
        assertTrue((firstOk ?: 99) <= 6, "never caught up in 31 fixes (firstOk=$firstOk); along fell to $minAlongAfterResume while real went 1130 → 1547")
    }
}
