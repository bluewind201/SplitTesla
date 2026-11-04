package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteReacquisitionTest {
    private fun point(n: Double, e: Double = 0.0) = LatLon(37.5 + n / 110574.0,
        127.0 + e / (111320.0 * kotlin.math.cos(Math.toRadians(37.5))))
    private fun route(path: List<LatLon>) = NaviRoute(RouteOption.FAST, path, emptyList(), emptyList(),
        0, 0, 0, NaviPlace("목적지", path.last()))

    @Test fun hairpinRecoveryUsesTheNewRealCourseWithoutSlidingBackwards() {
        val up = (0..120).map { point(it * 10.0) }
        val down = (120 downTo 0).map { point(it * 10.0, 24.0) }
        val t = RouteTracker(route(up + down))
        repeat(74) { i -> t.update(point(i * 15.0), 5.0, i * 1000L) }
        val before = t.alongM
        var recoveredAt: Int? = null
        repeat(20) { k ->
            val expected = 1410.0 + k * 15
            val p = t.update(point(2400.0 + 24.0 - expected, 24.0), 5.0, 94_000L + k * 1000)
            assertTrue(p.alongM >= before - 1, "progress regressed to ${p.alongM}")
            if (abs(p.alongM - expected) < 20 && recoveredAt == null) recoveredAt = k
            if (k < 5) assertTrue(p.positionUncertain)
            assertFalse(p.arrived)
        }
        assertNotNull(recoveredAt)
        assertTrue(recoveredAt <= 6)
        assertFalse(t.progress().positionUncertain)
    }

    @Test fun oppositeRealCourseWithoutAMatchingLegHoldsProgressAndExposesOffRoute() {
        val t = RouteTracker(route((0..100).map { point(it * 20.0) }))
        repeat(31) { i -> t.update(point(i * 20.0), 5.0, i * 1000L) }
        repeat(6) { k ->
            val p = t.update(point(580.0 - k * 20), 5.0, 31_000L + k * 1000)
            assertEquals(600.0, p.alongM, 0.1)
            assertTrue(p.positionUncertain)
            assertFalse(p.arrived)
        }
        assertTrue(t.progress().offRoute)
    }

    @Test fun smallReverseStepsCannotAccumulateIntoAnUnboundedRetreat() {
        val r = route((0..100).map { point(it * 20.0) })
        val t = RouteTracker(r)
        // Compatibility callers without course still cannot spend the 30 m jitter budget repeatedly.
        t.update(point(600.0))
        repeat(30) { k ->
            val p = t.update(point(595.0 - k * 5))
            assertTrue(p.alongM >= 570.0)
        }
        assertTrue(t.progress().positionUncertain)
        assertTrue(t.progress().offRoute)
    }

    @Test fun aCourseNeedsEnoughActualMovementForItsAccuracy() {
        val g = FixGate()
        repeat(25) { i ->
            val fix = assertNotNull(g.accept(point(if (i % 2 == 0) 0.0 else 6.0), i * 1000L, 8.0))
            assertNull(fix.courseDeg)
        }
    }

    @Test fun reacquiredCourseComesFromTheNewCandidateTrackNotTheOldNetDisplacement() {
        val g = FixGate()
        repeat(11) { i -> g.accept(point(i * 20.0), i * 1000L, 5.0) }
        var recovered: FixGate.Accepted? = null
        repeat(6) { k ->
            val fix = g.accept(point(350.0 - k * 15, 30.0), 31_000L + k * 1000, 5.0)
            if (k < 5) assertNull(fix) else recovered = fix
        }
        val fix = assertNotNull(recovered)
        assertTrue(fix.reacquired)
        assertFalse(fix.continuous)
        assertTrue(assertNotNull(fix.courseDeg) in 170.0..190.0)
    }

    @Test fun regularSparseCadenceRecoversAndThenKeepsAcceptingRealSamples() {
        for (period in listOf(10_000L, 15_000L, 30_000L)) {
            val g = FixGate()
            var count = 0
            repeat(19) { i ->
                val time = i * period
                if (g.accept(point(time / 1000.0 * 15), time, 5.0) != null) count++
            }
            assertTrue(count > 12, "period=$period accepted=$count")
        }
    }

    @Test fun aSuddenLongGapIsNotTreatedAsAnEstablishedSparseCadence() {
        val g = FixGate()
        repeat(6) { i -> g.accept(point(i * 20.0), i * 1000L, 5.0) }
        repeat(5) { k -> assertNull(g.accept(point(500.0 + k * 20), 25_000L + k * 1000, 5.0)) }
        val fix = assertNotNull(g.accept(point(600.0), 30_000, 5.0))
        assertTrue(fix.reacquired)
        assertFalse(fix.continuous)
    }

    @Test fun equalClaimedStartupAccuracyDoesNotChooseTheTrueClusterByThreeRejections() {
        val g = FixGate()
        g.accept(point(500.0), 0, 5.0)
        repeat(3) { k -> assertNull(g.accept(point((k + 1) * 20.0), (k + 1) * 1000L, 5.0)) }
    }
}
