package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeakFixAnchorPolicyTest {
    private fun point(m: Double, east: Double = 0.0) = LatLon(37.5 + m / 110574.0,
        127.0 + east / (111320.0 * kotlin.math.cos(Math.toRadians(37.5))))
    private val route = NaviRoute(RouteOption.FAST, (0..40).map { point(it * 100.0) }, emptyList(),
        listOf(RouteGuide(30, 3, "우회전", 3000, 180_000)), 4000, 240_000, 0, NaviPlace("목적지", point(4000.0)))
    private fun seeded() = RouteTracker(route).also { t ->
        repeat(11) { i -> t.update(point(i * 20.0), 5.0, i * 1000L) }
    }

    @Test fun vagueSidewaysObservationNeitherPoisonsTheGoodMotionTrackNorCancelsItsAnchor() {
        val t = seeded()
        assertNull(t.validateFix(point(220.0, 90.0), 50.0, 11_000))
        t.invalidatePositionEvidence()
        assertEquals(200.0, t.alongM, 0.01)
        assertFalse(t.progress().offRoute)
        val predicted = t.advance(10.0)
        assertTrue(predicted.positionUncertain)
        assertTrue(predicted.canAnnounceTurn)
        assertFalse(predicted.arrived)
        val good = assertNotNull(t.validateFix(point(240.0), 5.0, 12_000))
        assertEquals(72.0, good.derivedKmh!!, 0.1)
        assertEquals(0.0, good.courseDeg!!, 0.1)
        val real = t.update(point(240.0), 5.0, 12_000, acceptedFix = good)
        assertFalse(real.positionUncertain)
        assertFalse(real.estimated)
    }

    @Test fun vagueOnRouteObservationsDoNotReconfirmOrMoveTheRealPosition() {
        val t = seeded()
        t.update(point(220.0), 80.0, 11_000)
        val poor = t.update(point(240.0), 50.0, 12_000)
        assertEquals(200.0, poor.alongM, 0.01)
        assertTrue(poor.positionUncertain)
        assertFalse(poor.canAnnounceTurn)
        assertFalse(poor.arrived)
        assertTrue(t.advance(10.0).canAnnounceTurn)
    }

    @Test fun timeSpentReceivingVagueFixesConsumesTheOriginalAnchorLifetime() {
        val t = seeded()
        repeat(25) { k -> t.update(point(220.0 + k * 20), 50.0, 11_000L + k * 1000) }
        assertEquals(200.0, t.alongM, 0.01)
        repeat(5) { assertTrue(t.advance(10.0).canAnnounceTurn) }
        repeat(5) { assertFalse(t.advance(10.0).canAnnounceTurn) }
        assertTrue(t.progress().positionUncertain)
        assertFalse(t.progress().arrived)
    }

    @Test fun accurateContradictoryFixesStillCancelTheAnchorAndCountRepeatedOffRoute() {
        val t = seeded()
        repeat(3) { k -> t.update(point(220.0 + k * 20, 90.0), 30.0, 11_000L + k * 1000) }
        assertTrue(t.progress().offRoute)
        t.update(point(200.0), 50.0, 14_000)
        val p = t.advance(10.0)
        assertTrue(p.offRoute)
        assertTrue(p.positionUncertain)
        assertFalse(p.canAnnounceTurn)
    }

    @Test fun navigationAccuracyAdmissionPreservesTheBoundaryAndGenericGateCompatibility() {
        val nav = FixGate(maxAccuracyM = RouteTracker.TRUSTED_ACCURACY_M)
        assertNotNull(nav.accept(point(0.0), 0, 30.0))
        assertNull(nav.accept(point(90.0), 1000, 30.0001))
        val next = assertNotNull(nav.accept(point(20.0), 2000, 5.0))
        assertEquals(36.0, next.derivedKmh!!, 0.1)
        assertNotNull(FixGate().accept(point(0.0), 0, 50.0))
    }

    @Test fun aBroadExternalAdmissionCannotPromoteAPoorFixToARealRouteAnchor() {
        val t = seeded()
        val broad = assertNotNull(FixGate().accept(point(220.0), 11_000, 50.0))
        val p = t.update(point(220.0), 50.0, 11_000, acceptedFix = broad)
        assertEquals(200.0, p.alongM, 0.01)
        assertTrue(p.positionUncertain)
        assertFalse(p.arrived)
    }

    @Test fun receiptLatencyDoesNotExtendTheLastGoodSampleDeadline() {
        val sampledAt = assertNotNull(SpeedEvidence.fixSampleAtMs(50_000, 2_000_000_000))
        assertEquals(48_000L, sampledAt)
        assertNotNull(SpeedEvidence.deadReckoningKmh(78_000, sampledAt, 60.0, 78_000))
        assertNull(SpeedEvidence.deadReckoningKmh(78_001, sampledAt, 60.0, 78_001))
        assertNotNull(SpeedEvidence.deadReckoningKmh(78_001, 50_000, 60.0, 78_001))
        assertNull(SpeedEvidence.fixSampleAtMs(50_000, -1))
        assertNull(SpeedEvidence.fixSampleAtMs(50_000, 3_000_000_001))
        assertNull(SpeedEvidence.fixSampleAtMs(1000, 2_000_000_000))
    }

    @Test fun weakOrOutOfOrderObservationsCannotReopenAnExpiredPrediction() {
        val t = seeded()
        repeat(31) { t.advance(10.0) }
        assertFalse(t.progress().canAnnounceTurn)
        t.update(point(220.0), 50.0, 11_000)
        t.update(point(200.0), 50.0, 5000)
        assertFalse(t.advance(10.0).canAnnounceTurn)
        assertEquals(false, RouteTracker(route).update(point(500.0), 50.0, 1000).canAnnounceTurn)
    }
}
