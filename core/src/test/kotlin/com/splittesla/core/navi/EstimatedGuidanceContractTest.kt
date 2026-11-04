package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstimatedGuidanceContractTest {
    private fun point(m: Double) = LatLon(37.5 + m / 110574.0, 127.0)
    private val route = NaviRoute(RouteOption.FAST, (0..20).map { point(it * 100.0) }, emptyList(),
        listOf(RouteGuide(10, 3, "우회전", 1000, 60_000), RouteGuide(20, 88, "목적지", 1000, 60_000)),
        2000, 120_000, 0, NaviPlace("목적지", point(2000.0)))
    private fun seeded() = RouteTracker(route).also { t ->
        repeat(11) { i -> t.update(point(i * 20.0), 5.0, i * 1000L) }
    }

    @Test fun aRejectedGpsFixAllowsOnlyExplicitEstimatedNoticesFromTheLastRouteAnchor() {
        val t = seeded()
        t.update(point(220.0), 80.0, 11_000)
        assertTrue(t.progress().positionUncertain)
        assertFalse(t.progress().canAnnounceTurn)
        val a = GuidanceAnnouncer()
        val said = ArrayList<String>()
        repeat(20) {
            val p = t.advance(20.0)
            assertTrue(p.estimated)
            assertTrue(p.positionUncertain)
            assertTrue(p.estimatedTurnAllowed)
            assertTrue(p.canAnnounceTurn)
            assertFalse(p.arrived)
            a.next(route, p, 72.0)?.let { said += it }
        }
        assertTrue(said.any { it.contains("우회전") })
        assertTrue(said.all { it.startsWith("추정 위치 기준, ") })
    }

    @Test fun contraryRealRouteEvidenceCannotBeBypassedByStartingDeadReckoning() {
        val t = seeded()
        repeat(3) { k -> t.update(point(180.0 - k * 20), 5.0, 11_000L + k * 1000) }
        assertTrue(t.progress().positionUncertain)
        assertTrue(t.progress().offRoute)
        val p = t.advance(20.0)
        assertTrue(p.estimated)
        assertTrue(p.positionUncertain)
        assertTrue(p.offRoute)
        assertFalse(p.canAnnounceTurn)
        assertNull(GuidanceAnnouncer().next(route, p, 72.0))
    }

    @Test fun predictionPermissionExpiresAndOnlyAnAcceptedRealFixResetsItsBudget() {
        val t = seeded()
        t.invalidatePositionEvidence()
        repeat(30) { assertTrue(t.advance(10.0).estimatedTurnAllowed) }
        assertFalse(t.advance(10.0).canAnnounceTurn)
        assertNull(GuidanceAnnouncer().next(route, t.progress(), 36.0))
        repeat(6) { k -> t.update(point(500.0 + k * 10), 5.0, 45_000L + k * 1000) }
        assertFalse(t.progress().positionUncertain)
        assertFalse(t.progress().estimated)
        assertTrue(t.advance(10.0).estimatedTurnAllowed)
    }

    @Test fun vehicleSpeedExpiryWithdrawsTurnPermissionWithoutMakingGpsCertain() {
        val t = seeded()
        t.invalidatePositionEvidence()
        val speed = assertNotNull(SpeedEvidence.deadReckoningKmh(14_000, 10_000, 90.0, 14_000))
        assertTrue(t.advance(speed / 3.6).canAnnounceTurn)
        assertNull(SpeedEvidence.deadReckoningKmh(41_000, 10_000, 90.0, 41_000))
        t.invalidateEstimatedTurn()
        assertFalse(t.progress().canAnnounceTurn)
        assertTrue(t.progress().estimated)
        assertTrue(t.progress().positionUncertain)
        assertFalse(t.progress().arrived)
    }

    @Test fun malformedOrUnauthorizedStepsNeverGrantEstimatedTurnPermission() {
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 500.0)) {
            assertFalse(seeded().advance(distance).canAnnounceTurn, "distance=$distance")
        }
        assertFalse(seeded().advance(10.0, elapsedMs = 0).canAnnounceTurn)
        assertFalse(seeded().advance(10.0, elapsedMs = 3000).canAnnounceTurn)
        assertFalse(seeded().advance(10.0, turnPredictionValid = false).canAnnounceTurn)
        val unanchored = RouteTracker(route)
        assertFalse(unanchored.progress().canAnnounceTurn)
        assertFalse(unanchored.advance(10.0).canAnnounceTurn)
    }

    @Test fun anEstimatedArrivalFlagCannotBeUsedAsAnActualArrivalAnnouncement() {
        val t = RouteTracker(route)
        t.update(point(1900.0), 5.0, 1000)
        t.invalidatePositionEvidence()
        repeat(5) { t.advance(30.0) }
        val p = t.progress()
        assertTrue(p.estimatedTurnAllowed)
        assertTrue(p.estimated)
        assertTrue(p.positionUncertain)
        assertFalse(p.arrived)
        assertNull(GuidanceAnnouncer().next(route, p.copy(arrived = true), 108.0))
    }

    @Test fun actualFixRecoveryRestoresActualConfidenceAndUnprefixedGuidance() {
        val t = seeded()
        t.invalidatePositionEvidence()
        repeat(10) { t.advance(20.0) }
        assertTrue(assertNotNull(GuidanceAnnouncer().next(route, t.progress(), 72.0)).startsWith("추정 위치 기준"))
        val real = t.update(point(350.0), 5.0, 13_000)
        assertFalse(real.estimated)
        assertFalse(real.positionUncertain)
        assertFalse(real.estimatedTurnAllowed)
        assertTrue(real.canAnnounceTurn)
        assertFalse(assertNotNull(GuidanceAnnouncer().next(route, real, 72.0)).startsWith("추정 위치 기준"))
    }
}
