package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixGateTest {
    private fun point(m: Double) = LatLon(37.5 + m / 110574.0, 127.0)

    @Test fun ignoresMissingInvalidFutureAndStaleMeasuredSpeeds() {
        assertNull(SpeedEvidence.measured(Double.NaN))
        assertNull(SpeedEvidence.measured(Double.POSITIVE_INFINITY))
        assertNull(SpeedEvidence.measured(-1.0))
        assertNull(SpeedEvidence.fresh(90.0, 1000, 5000))
        assertNull(SpeedEvidence.fresh(90.0, 6000, 5000))
        assertEquals(0.0, SpeedEvidence.fresh(0.0, 4000, 5000))
    }

    @Test fun deadReckoningRequiresFreshVehicleSpeedAndExpiresEvenWithFreshBle() {
        assertNull(SpeedEvidence.deadReckoningKmh(10_000, 1000, 100.0, 2000))
        assertEquals(100.0, SpeedEvidence.deadReckoningKmh(10_000, 1000, 100.0, 9500))
        assertNull(SpeedEvidence.deadReckoningKmh(32_000, 1000, 100.0, 31_500))
        assertNull(SpeedEvidence.deadReckoningKmh(10_000, 1000, null, 9500))
    }

    @Test fun rejectsInvalidInaccurateAndNonMonotonicFixes() {
        val g = FixGate()
        assertNull(g.accept(LatLon(Double.NaN, 127.0), 0))
        assertNull(g.accept(point(0.0), 0, Double.NaN))
        assertNull(g.accept(point(0.0), 0, 80.0))
        assertNotNull(g.accept(point(0.0), 1000, 5.0))
        assertNull(g.accept(point(20.0), 1000, 5.0))
        assertNull(g.accept(point(20.0), 900, 5.0))
        assertEquals(72.0, g.accept(point(20.0), 2000, 5.0)!!.derivedKmh!!, 0.1)
    }

    @Test fun repeatedUnrelatedJumpsNeverBecomeAcceptedByCount() {
        val g = FixGate()
        g.accept(point(0.0), 0, 5.0)
        repeat(40) { i -> assertNull(g.accept(point(if (i % 2 == 0) 1000.0 else 2000.0), (i + 1) * 1000L, 5.0)) }
    }

    @Test fun elapsedTimeAloneDoesNotLegalizeTheSameModerateJump() {
        val g = FixGate()
        g.accept(point(0.0), 0, 5.0)
        for (i in 1..9) assertNull(g.accept(point(300.0), i * 1000L, 5.0))
        val reacquired = assertNotNull(g.accept(point(300.0), 10_000, 5.0))
        assertTrue(reacquired.reacquired)
        assertFalse(reacquired.continuous)
    }

    @Test fun oscillatingCandidateMotionDoesNotReacquire() {
        val g = FixGate()
        g.accept(point(0.0), 0, 5.0)
        repeat(30) { i -> assertNull(g.accept(point(if (i % 2 == 0) 1000.0 else 1040.0), (i + 1) * 1000L, 5.0)) }
    }

    @Test fun aSingleSpikeDoesNotPoisonTheNextRealFix() {
        val g = FixGate()
        g.accept(point(0.0), 0, 5.0)
        assertNull(g.accept(point(1000.0), 1000, 5.0))
        val recovered = assertNotNull(g.accept(point(40.0), 2000, 5.0))
        assertFalse(recovered.reacquired)
        assertTrue(recovered.continuous)
    }

    @Test fun reacquisitionRequiresATimedCoherentCandidateAndBreaksDistanceEvidence() {
        val g = FixGate()
        g.accept(point(0.0), 0, 5.0)
        for (i in 1..9) assertNull(g.accept(point(1000.0 + i * 20), i * 1000L, 5.0))
        val recovered = assertNotNull(g.accept(point(1200.0), 10_000, 5.0))
        assertTrue(recovered.reacquired)
        assertFalse(recovered.continuous)
        assertNull(recovered.derivedKmh)
    }
}
