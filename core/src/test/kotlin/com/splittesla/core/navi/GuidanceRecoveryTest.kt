package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidanceRecoveryTest {
    private fun point(north: Double, east: Double = 0.0) =
        LatLon(37.5 + north / 110574.0, 127.0 + east / (111320.0 * kotlin.math.cos(Math.toRadians(37.5))))
    private fun route(path: List<LatLon>) = NaviRoute(RouteOption.FAST, path, emptyList(), emptyList(),
        0, 0, 0, NaviPlace("도착점", path.last()))

    @Test fun regularSparseFixesAndSparseRecoveryBothRemainUsable() {
        for (period in listOf(4000L, 6000L, 8000L)) for (metresPerSecond in listOf(25.0, 69.0)) {
            val g = FixGate()
            repeat(15) { i ->
                val t = i * period
                assertNotNull(g.accept(point(t / 1000.0 * metresPerSecond), t, 5.0), "period=$period speed=$metresPerSecond t=$t")
            }
        }
        val g = FixGate()
        g.accept(point(0.0), 0, 5.0)
        var recovered = false
        repeat(6) { i ->
            val t = 20_000L + i * 6000
            if (g.accept(point(t / 1000.0 * 25), t, 5.0) != null) recovered = true
        }
        assertTrue(recovered)
    }

    @Test fun trajectoryEvidenceRecoversAfterOneSpikeAndMissingSamples() {
        val g = FixGate()
        repeat(6) { i -> assertNotNull(g.accept(point(i * 25.0), i * 1000L, 5.0)) }
        assertNull(g.accept(point(625.0), 6000, 5.0))
        val resumed = assertNotNull(g.accept(point(225.0), 9000, 5.0))
        assertTrue(resumed.reacquired)
        assertFalse(resumed.continuous)
        assertNull(resumed.derivedKmh)
        assertNotNull(g.accept(point(250.0), 10_000, 5.0))
    }

    @Test fun extrapolationNeverRelaxesThePhysicalReachBound() {
        val g = FixGate()
        g.accept(point(0.0), 0, 20.0)
        assertNotNull(g.accept(point(50.0), 500, 20.0))
        // A noisy short baseline can imply 100 m/s; it cannot authorize an 800 m jump.
        assertNull(g.accept(point(850.0), 8500, 20.0))
    }

    @Test fun anIsolatedWeakFirstAnchorCanBeReplacedByBetterTimedEvidence() {
        val g = FixGate()
        g.accept(point(600.0), 0, 25.0)
        assertNull(g.accept(point(20.0), 1000, 5.0))
        assertNull(g.accept(point(40.0), 2000, 5.0))
        assertTrue(assertNotNull(g.accept(point(60.0), 3000, 5.0)).reacquired)
        // Equal quality alone is insufficient to declare which disconnected cluster is real.
        val ambiguous = FixGate()
        ambiguous.accept(point(600.0), 0, 5.0)
        for (i in 1..3) assertNull(ambiguous.accept(point(i * 20.0), i * 1000L, 5.0))
    }

    @Test fun tunnelRecoveryFollowsTheNearbyOutboundLegAcrossSamplingBiases() {
        for (bias in listOf(4.0, 7.0, 9.0)) {
            val out = (0..30).map { point(it * 100.0) }
            val back = (30 downTo 0).map { point(it * 100.0, 12.0) }
            val t = RouteTracker(route(out + back))
            repeat(26) { i -> t.update(point(i * 20.0), 5.0, i * 1000L) }
            repeat(20) { t.advance(20.0) }
            repeat(20) { i ->
                val p = t.update(point(920.0 + i * 20, bias), 10.0, 46_000L + i * 1000)
                assertTrue(p.alongM < 1500, "bias=$bias progress=${p.alongM}")
                assertFalse(p.arrived)
            }
        }
    }

    @Test fun plausibleSparseProgressIsNotClampedToTheLocalWindow() {
        val t = RouteTracker(route((0..25).map { point(it * 100.0) }))
        t.update(point(0.0), 5.0, 0)
        assertEquals(180.0, t.update(point(180.0), 5.0, 3000).alongM, 0.1)
    }

    @Test fun anUnusableRealFixKeepsTheLastPositionEstimated() {
        val t = RouteTracker(route((0..25).map { point(it * 100.0) }))
        repeat(6) { i -> t.update(point(i * 20.0), 5.0, i * 1000L) }
        t.advance(300.0)
        assertTrue(t.progress().estimated)
        assertTrue(t.update(point(2000.0), 5.0, 6000).estimated)
        assertTrue(t.update(point(2000.0), 80.0, 7000).estimated)
        assertFalse(t.progress().arrived)
    }

    @Test fun parkingFortyFiveMetresBesideTheEndDoesNotExpandAutomaticArrival() {
        val t = RouteTracker(route((0..20).map { point(it * 100.0) }))
        repeat(99) { i -> t.update(point(i * 20.0), 5.0, i * 1000L) }
        repeat(61) { i ->
            assertFalse(t.update(point(1995.0, 45.0), 10.0, 99_000L + i * 1000).arrived)
        }
    }

    @Test fun transientArrivalDoesNotPromiseThatGuidanceHasEnded() {
        val r = route((0..10).map { point(it * 100.0) })
        val t = RouteTracker(r)
        val a = GuidanceAnnouncer()
        t.update(point(995.0), 5.0, 1000)
        val spoken = assertNotNull(a.next(r, t.update(point(997.0), 5.0, 2000), 50.0))
        assertFalse(spoken.contains("안내를 마칩니다"))
        assertFalse(t.update(point(1060.0), 5.0, 4000).arrived)
    }
}
