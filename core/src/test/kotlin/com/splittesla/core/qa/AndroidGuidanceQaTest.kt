package com.splittesla.core.qa

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.camera.CameraAlert
import com.splittesla.core.camera.CameraAlerter
import com.splittesla.core.camera.CameraCsv
import com.splittesla.core.camera.CameraIndex
import com.splittesla.core.camera.SectionRole
import com.splittesla.core.camera.SpeedCamera
import com.splittesla.core.camera.spokenText
import com.splittesla.core.navi.FixGate
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteGuide
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteTracker
import com.splittesla.core.navi.SpeedEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent QA counterexamples (docs/android-guidance/qa.md). Every test here
 * asserts behaviour that was observed to hold. Reproductions of defects live in
 * [AndroidGuidanceQaDefectsTest] and are skipped unless QA_REPRO=1.
 */
class AndroidGuidanceQaTest {
    // ── Speed evidence ──

    @Test fun speedEvidenceRejectsNanInfiniteNegativeOverRangeFutureZeroAndStale() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 250.1)) {
            assertNull(SpeedEvidence.measured(bad), "measured($bad)")
        }
        assertNull(SpeedEvidence.measured(null))
        assertEquals(250.0, SpeedEvidence.measured(250.0))
        val now = 100_000L
        assertNull(SpeedEvidence.fresh(80.0, now + 1, now), "future sample")
        assertNull(SpeedEvidence.fresh(80.0, 0, now), "never received")
        assertNull(SpeedEvidence.fresh(80.0, now - 3001, now), "3.001 s old")
        assertEquals(80.0, SpeedEvidence.fresh(80.0, now - 3000, now))
        assertNull(SpeedEvidence.fresh(Double.NaN, now, now))
    }

    @Test fun deadReckoningWindowIsBoundedOnBothSides() {
        val fixAt = 1_000_000L
        assertNull(SpeedEvidence.deadReckoningKmh(fixAt + 3000, fixAt, 90.0, fixAt + 3000), "GPS still fresh")
        assertEquals(90.0, SpeedEvidence.deadReckoningKmh(fixAt + 3001, fixAt, 90.0, fixAt + 3001))
        assertEquals(90.0, SpeedEvidence.deadReckoningKmh(fixAt + 30_000, fixAt, 90.0, fixAt + 29_000))
        assertNull(SpeedEvidence.deadReckoningKmh(fixAt + 30_001, fixAt, 90.0, fixAt + 30_001), "30 s cap")
        assertNull(SpeedEvidence.deadReckoningKmh(fixAt + 10_000, 0, 90.0, fixAt + 10_000), "no real fix yet")
        assertNull(SpeedEvidence.deadReckoningKmh(fixAt + 10_000, fixAt, 90.0, fixAt + 6_000), "stale BLE")
        assertNull(SpeedEvidence.deadReckoningKmh(fixAt + 10_000, fixAt, 90.0, fixAt + 11_000), "future BLE")
    }

    // ── FixGate ──

    private fun north(m: Double, lat0: Double = 37.5) = LatLon(lat0 + m / 110574.0, 127.0)

    @Test fun highSpeedAt1HzIsNeverRejected() {
        for (kmh in listOf(130.0, 200.0, 250.0)) {
            val g = FixGate()
            val v = kmh / 3.6
            var rejected = 0
            for (i in 0..60) if (g.accept(north(i * v), i * 1000L, 5.0) == null) rejected++
            assertEquals(0, rejected, "$kmh km/h")
        }
    }

    @Test fun lowRateFixesUpToFiveSecondsApartAreAccepted() {
        val g = FixGate()
        val v = 100 / 3.6
        var rejected = 0
        for (i in 0..20) if (g.accept(north(i * 5 * v), i * 5000L, 5.0) == null) rejected++
        assertEquals(0, rejected)
    }

    @Test fun stationaryJitterIsAcceptedWithoutBecomingAReacquire() {
        val g = FixGate()
        var reacq = 0
        for (i in 0..30) {
            val a = g.accept(north(if (i % 2 == 0) 0.0 else 8.0), i * 1000L, 10.0)
            assertNotNull(a)
            if (a.reacquired) reacq++
        }
        assertEquals(0, reacq)
    }

    @Test fun alternatingTrueAndJumpedFixesNeverAcceptTheJump() {
        val g = FixGate()
        val v = 60 / 3.6
        for (i in 0..40) {
            val t = i * 1000L
            val a = if (i % 2 == 0) g.accept(north(i * v), t, 5.0) else g.accept(north(i * v + 400), t, 5.0)
            if (i % 2 == 1) assertNull(a, "jump at $i accepted")
            else assertNotNull(a, "true fix at $i rejected")
        }
    }

    @Test fun duplicateAndBackwardsTimestampsAreRejected() {
        val g = FixGate()
        assertNotNull(g.accept(north(0.0), 5000, 5.0))
        assertNull(g.accept(north(10.0), 5000, 5.0))
        assertNull(g.accept(north(10.0), 4000, 5.0))
        assertNull(g.accept(north(10.0), -1, 5.0))
        assertNull(g.accept(LatLon(91.0, 127.0), 6000, 5.0))
        assertNull(g.accept(north(10.0), 7000, 60.0001))
        assertNotNull(g.accept(north(30.0), 8000, 60.0))
    }

    @Test fun tunnelExitReacquiresWithinAboutSixSecondsAt1Hz() {
        val g = FixGate()
        val v = 80 / 3.6
        for (i in 0..5) assertNotNull(g.accept(north(i * v), i * 1000L, 5.0))
        // 20 s outage, then fixes return at 1 Hz.
        var firstAccepted: Int? = null
        for (k in 0..15) {
            val t = 25_000L + k * 1000
            val a = g.accept(north(25 * v + k * v), t, 5.0)
            if (a != null && firstAccepted == null) {
                firstAccepted = k
                assertTrue(a.reacquired)
                assertFalse(a.continuous)
                assertNull(a.derivedKmh)
            }
        }
        assertNotNull(firstAccepted)
        assertTrue(firstAccepted <= 6, "first accepted at k=$firstAccepted")
    }

    // ── CameraAlerter ──

    private val csv = """
        무인교통단속카메라관리번호,시도명,설치장소,위도,경도,단속구분,제한속도,단속구간위치구분,과속단속구간길이,보호구역구분
        C1,서울특별시,역삼역 앞,37.5000,127.0000,1,60,,,
        C3,경기도,구간 시작,37.6000,127.0000,1,100,구간시작,3000,
        C4,경기도,구간 끝,37.6270,127.0000,1,100,구간종료,3000,
    """.trimIndent()

    private fun cams() = CameraCsv.parse(csv)

    private fun driveNorth(
        a: CameraAlerter, fromLat: Double, toLat: Double, kmh: Double,
        everyMs: Long = 1000, speed: Double? = kmh, heading: Double? = 0.0, startMs: Long = 0,
    ): List<CameraAlert> {
        val step = kmh / 3.6 * everyMs / 1000.0 / 111_195.0
        val out = ArrayList<CameraAlert>()
        var lat = fromLat
        var t = startMs
        while (lat < toLat) {
            out += a.update(lat, 127.0, heading, speed ?: Double.NaN, t, measuredSpeedKmh = speed)
            lat += step
            t += everyMs
        }
        return out
    }

    @Test fun missingSpeedAndMissingBearingStillWarnFromPositions() {
        val a = CameraAlerter(CameraIndex(cams()))
        val alerts = driveNorth(a, 37.49, 37.5005, 60.0, speed = null, heading = null)
        assertTrue(alerts.any { it is CameraAlert.Final }, "$alerts")
        assertTrue(alerts.none { (it as? CameraAlert.Final)?.overLimit == true || (it as? CameraAlert.Approaching)?.overLimit == true })
    }

    @Test fun stationaryNearACameraWithoutSpeedDoesNotWarn() {
        val a = CameraAlerter(CameraIndex(cams()))
        val alerts = ArrayList<CameraAlert>()
        // Parked 150 m south of C1; hasSpeed missing; GPS jitters 6 m along the road.
        for (i in 0..60) {
            val m = if (i % 2 == 0) 0.0 else 6.0
            alerts += a.update(37.5 - 150 / 111195.0 + m / 111195.0, 127.0, 0.0, Double.NaN, i * 1000L, measuredSpeedKmh = null, accuracyM = 8.0)
        }
        assertTrue(alerts.isEmpty(), "$alerts")
    }

    @Test fun eachCameraIsAnnouncedAtMostOncePerStageEvenWithASpikeInBetween() {
        val a = CameraAlerter(CameraIndex(cams()))
        val alerts = ArrayList<CameraAlert>()
        alerts += driveNorth(a, 37.49, 37.4985, 60.0)
        alerts += a.update(37.52, 127.0, 0.0, 60.0, 1_000_000) // rejected spike
        alerts += driveNorth(a, 37.4985, 37.5005, 60.0, startMs = 1_001_000)
        assertTrue(alerts.count { it is CameraAlert.Final && it.camera.id == "C1" } <= 1, "$alerts")
        assertTrue(alerts.count { it is CameraAlert.Approaching && it.camera.id == "C1" } <= 1, "$alerts")
    }

    @Test fun emptyRouteFilterSilencesAndNullFilterWarns() {
        val empty = CameraAlerter(CameraIndex(cams())).apply { routeCameraIds = emptySet() }
        assertTrue(driveNorth(empty, 37.49, 37.5005, 60.0).isEmpty())
        val none = CameraAlerter(CameraIndex(cams())).apply { routeCameraIds = null }
        assertTrue(driveNorth(none, 37.49, 37.5005, 60.0).any { it is CameraAlert.Final })
    }

    @Test fun unverifiedSectionAt1HzSaysStartAndUnverifiedEndWithoutNumbers() {
        val a = CameraAlerter(CameraIndex(cams()))
        val alerts = driveNorth(a, 37.595, 37.63, 100.0)
        assertTrue(alerts.any { it is CameraAlert.SectionStart })
        val end = alerts.filterIsInstance<CameraAlert.SectionEnd>().single()
        assertFalse(end.averageVerified)
        assertEquals("구간단속 종료 지점, 평균 속도 미확인", end.spokenText())
        assertNull(a.status.sectionAverageKmh)
        assertNull(a.status.sectionSafeKmh)
        assertNull(a.status.sectionRemainingM)
    }

    @Test fun verifiedPairMeasuresOnlyWhileEvidenceIsContinuous() {
        val a = CameraAlerter(CameraIndex(cams())).apply { verifiedSectionEnds = mapOf("C3" to "C4") }
        val alerts = driveNorth(a, 37.595, 37.63, 100.0)
        val end = alerts.filterIsInstance<CameraAlert.SectionEnd>().single()
        assertTrue(end.averageVerified)
        assertEquals(100.0, end.averageKmh, 5.0)
    }

    @Test fun spokenDistancesFollowTheRealDistance() {
        val c = cams().first()
        assertTrue(CameraAlert.Final(c, 97.0, false).spokenText().startsWith("100미터 앞"))
        assertTrue(CameraAlert.Final(c, 63.0, false).spokenText().startsWith("60미터 앞"))
        assertTrue(CameraAlert.Final(c, 3.0, false).spokenText().startsWith("10미터 앞"))
        assertTrue(CameraAlert.Approaching(c, 286.0, false).spokenText().startsWith("300미터 앞"))
        assertFalse(CameraAlert.Approaching(c, 286.0, false).spokenText().contains("줄이세요"))
    }

    /** 1 Hz fixes at 160 km/h (44 m apart) still catch the START pass at every phase offset. */
    @Test fun sectionStartIsAnnouncedAt1HzOnAHighwayAtEveryPhase() {
        val csv = """
            무인교통단속카메라관리번호,시도명,설치장소,위도,경도,단속구분,제한속도,단속구간위치구분,과속단속구간길이,보호구역구분
            C3,경기도,구간 시작,37.6000,127.0000,1,100,구간시작,3000,
        """.trimIndent()
        var missed = 0
        val offsets = (0 until 9).map { it * 5.0 }
        for (off in offsets) {
            val a = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
            val v = 160 / 3.6
            var lat = 37.594 + off / 111195.0
            var t = 0L
            val alerts = ArrayList<CameraAlert>()
            while (lat < 37.602) {
                alerts += a.update(lat, 127.0, 0.0, 160.0, t)
                lat += v / 111195.0
                t += 1000
            }
            if (alerts.none { it is CameraAlert.SectionStart }) missed++
        }
        assertEquals(0, missed, "SectionStart missed in $missed of ${offsets.size} phase offsets")
    }

    // ── RouteTracker ──

    private val start = LatLon(37.5663, 126.9779)
    private val dLat = 0.0009044 // ~100 m
    private val path = (0..20).map { LatLon(start.lat + it * dLat, start.lon) }
    private val route = NaviRoute(
        option = RouteOption.FAST, path = path, sections = emptyList(),
        guides = listOf(RouteGuide(10, 3, "우회전", 1000, 72_000), RouteGuide(20, 88, "목적지", 1000, 300_000)),
        distanceM = 2000, durationMs = 372_000, tollFare = 0, goal = NaviPlace("목적지", path.last()),
    )
    private fun on(m: Double, eastM: Double = 0.0) =
        LatLon(start.lat + m / 110574.0, start.lon + eastM / (111320.0 * Math.cos(Math.toRadians(start.lat))))

    @Test fun normalDriveProgressesAndArrivesOnlyAfterRealStops() {
        val t = RouteTracker(route)
        val v = 50 / 3.6
        var anyArrivedBeforeEnd = false
        var i = 0
        while (i * v < 1960) {
            val p = t.update(on(i * v), 5.0, i * 1000L)
            if (p.arrived) anyArrivedBeforeEnd = true
            i++
        }
        assertFalse(anyArrivedBeforeEnd)
        assertEquals(1960.0, t.progress().alongM, 20.0)
        t.update(on(1995.0), 5.0, i * 1000L)
        assertTrue(t.update(on(1996.0), 5.0, i * 1000L + 1000).arrived)
    }

    @Test fun deadReckoningIgnoresNanAndInfiniteDistancesAndNeverArrives() {
        val t = RouteTracker(route)
        t.update(on(0.0), 5.0, 0)
        assertEquals(0.0, t.advance(Double.NaN).alongM, 0.01)
        assertEquals(0.0, t.advance(Double.POSITIVE_INFINITY).alongM, 0.01)
        assertEquals(0.0, t.advance(-50.0).alongM, 0.01)
        val p = t.advance(5000.0)
        assertTrue(p.estimated)
        assertFalse(p.arrived)
    }

    @Test fun crossingLaterLegDoesNotPullProgressForward() {
        // Out 1 km north, east 300 m, south 600 m, west 600 m: the last leg crosses the first at 400 m.
        val eastStep = 100.0 / (111320.0 * Math.cos(Math.toRadians(start.lat)))
        val leg1 = (0..10).map { LatLon(start.lat + it * dLat, start.lon) }
        val leg2 = (1..3).map { LatLon(leg1.last().lat, start.lon + it * eastStep) }
        val leg3 = (1..6).map { LatLon(leg1.last().lat - it * dLat, leg2.last().lon) }
        val leg4 = (1..6).map { LatLon(leg3.last().lat, leg3.last().lon - it * eastStep) }
        val r = route.copy(path = leg1 + leg2 + leg3 + leg4, guides = emptyList(), goal = NaviPlace("x", leg4.last()))
        val t = RouteTracker(r)
        val v = 50 / 3.6
        for (i in 0..40) {
            val p = t.update(on(i * v), 5.0, i * 1000L)
            assertTrue(p.alongM < i * v + 50, "jumped to ${p.alongM} at ${i * v}")
            assertFalse(p.arrived)
        }
    }

    /**
     * QA-L1 (policy limit, PM decision msg_acd8ed3444da, not a fix): parked 45 m beside the
     * route end is indistinguishable from a parallel road, so arrival is not auto-confirmed;
     * the driver ends guidance manually ("안내 종료").
     */
    @Test fun policyLimitParkedFortyFiveMetresBesideTheEndIsNotAutoArrival() {
        val t = RouteTracker(route)
        val v = 50 / 3.6
        var i = 0
        while (i * v < 1990) { t.update(on(i * v), 5.0, i * 1000L); i++ }
        var arrived = false
        for (k in 0..60) if (t.update(on(1995.0, eastM = 45.0), 10.0, (i + k) * 1000L).arrived) arrived = true
        assertFalse(arrived)
    }

    @Test fun arrivalAnnouncementIsSpokenOnce() {
        val t = RouteTracker(route)
        val a = GuidanceAnnouncer()
        t.update(on(1995.0), 5.0, 0)
        val p = t.update(on(1996.0), 5.0, 1000)
        assertNotNull(a.next(route, p, 0.0))
        assertNull(a.next(route, t.update(on(1997.0), 5.0, 2000), 0.0))
    }
}
