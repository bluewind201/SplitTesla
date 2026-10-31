package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.camera.CameraIndex
import com.splittesla.core.camera.SpeedCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaviTest {
    // A straight 2 km road due north from Seoul City Hall, one point every ~100 m,
    // with a right turn guide at 1 km and the destination at the end.
    private val start = LatLon(37.5663, 126.9779)
    private val path = (0..20).map { LatLon(start.lat + it * 0.0009044, start.lon) }
    private val route = NaviRoute(
        option = RouteOption.FAST,
        path = path,
        sections = listOf(RouteSection(0, 10, 1000, "세종대로", 1, 50), RouteSection(10, 10, 1000, "사직로", 3, 12)),
        guides = listOf(
            RouteGuide(10, 3, "'광화문' 방면으로 우회전", 1000, 72_000),
            RouteGuide(20, 88, "목적지", 1000, 300_000),
        ),
        distanceM = 2000,
        durationMs = 372_000,
        tollFare = 0,
        goal = NaviPlace("경복궁", path.last()),
    )

    @Test fun geometryLengthIsTwoKilometres() {
        val g = RouteGeometry(path)
        assertEquals(2000.0, g.lengthM, 5.0)
        val mid = g.pointAt(1000.0)
        assertEquals(path[10].lat, mid.lat, 1e-5)
    }

    @Test fun progressFollowsTheCarAndCountsDownToTheTurn() {
        val t = RouteTracker(route)
        val p = t.update(LatLon(start.lat + 0.0009044 * 4, start.lon + 0.00005))
        assertEquals(400.0, p.alongM, 6.0)
        assertEquals(1600.0, p.remainingM, 6.0)
        assertEquals(0, p.nextGuideIndex)
        assertEquals(600.0, p.nextGuideDistanceM!!, 6.0)
        // 60% of the first leg left plus the whole second leg.
        assertEquals(0.6 * 72_000 + 300_000, p.remainingMs.toDouble(), 2_000.0)
        assertFalse(p.offRoute)
        assertEquals(0, p.sectionIndex)
    }

    @Test fun threeFixesFarFromTheLineMeanOffRoute() {
        val t = RouteTracker(route)
        val away = LatLon(start.lat + 0.002, start.lon + 0.002) // ~176 m east
        t.update(away)
        t.update(away)
        assertFalse(t.progress().offRoute)
        assertTrue(t.update(away).offRoute)
        // Back on the line clears it.
        assertFalse(t.update(LatLon(start.lat + 0.0025, start.lon)).offRoute)
    }

    @Test fun jitterBehindDoesNotMoveProgressBackwards() {
        val t = RouteTracker(route)
        t.update(LatLon(start.lat + 0.0009044 * 8, start.lon))
        val back = t.update(LatLon(start.lat + 0.0009044 * 5, start.lon))
        assertEquals(800.0, back.alongM, 6.0)
    }

    @Test fun deadReckoningMovesAlongTheLine() {
        val t = RouteTracker(route)
        t.update(start)
        val p = t.advance(1500.0)
        assertEquals(1500.0, p.alongM, 1.0)
        assertEquals(1, p.nextGuideIndex)
        assertTrue(t.advance(600.0).arrived)
    }

    @Test fun vagueFixesBesideTheRoadNeverMeanOffRoute() {
        val t = RouteTracker(route)
        val drift = LatLon(start.lat + 0.002, start.lon + 0.002) // ~176 m east, as in a tunnel mouth
        repeat(5) { t.update(drift, accuracyM = 80.0) }
        assertFalse(t.progress().offRoute)
    }

    @Test fun fixesRightAfterATunnelCannotStartANewSearch() {
        val t = RouteTracker(route)
        t.update(LatLon(start.lat + 0.0009044 * 2, start.lon))
        t.advance(300.0)
        val away = LatLon(start.lat + 0.006, start.lon + 0.002)
        repeat(RouteTracker.GRACE_FIXES) { assertFalse(t.update(away, accuracyM = 5.0).offRoute) }
        // Once the grace is used up, really being elsewhere still counts.
        repeat(3) { t.update(away, accuracyM = 5.0) }
        assertTrue(t.progress().offRoute)
    }

    @Test fun firstFixAfterATunnelPullsAnOvershootBack() {
        val t = RouteTracker(route)
        t.update(LatLon(start.lat + 0.0009044 * 4, start.lon))
        assertEquals(1000.0, t.advance(600.0).alongM, 6.0)
        val p = t.update(LatLon(start.lat + 0.0009044 * 8, start.lon + 0.00003), accuracyM = 6.0)
        assertEquals(800.0, p.alongM, 6.0)
    }

    @Test fun carIsDrawnOnTheLineFacingAlongTheRoad() {
        val t = RouteTracker(route)
        t.update(LatLon(start.lat + 0.0009044 * 4, start.lon + 0.0002), accuracyM = 10.0) // ~18 m east of the road
        assertEquals(start.lon, t.snapped().lon, 1e-6)
        assertEquals(0.0, t.heading(), 0.5)
        val east = RouteGeometry((0..5).map { LatLon(start.lat, start.lon + it * 0.001) })
        assertEquals(90.0, east.bearingAt(250.0), 0.5)
    }

    @Test fun announcerSpeaksEachStageOnceAndAnnouncesArrival() {
        val t = RouteTracker(route)
        val a = GuidanceAnnouncer()
        // Town speed: 700 m, 300 m, 80 m.
        val far = a.next(route, t.update(LatLon(start.lat + 0.0009044 * 3.5, start.lon)), 50.0)
        assertEquals("700미터 앞, 광화문 방면으로 우회전.", far)
        assertNull(a.next(route, t.update(LatLon(start.lat + 0.0009044 * 3.6, start.lon)), 50.0))
        val near = a.next(route, t.update(LatLon(start.lat + 0.0009044 * 7.5, start.lon)), 50.0)
        assertEquals("300미터 앞, 광화문 방면으로 우회전.", near)
        val now = a.next(route, t.update(LatLon(start.lat + 0.0009044 * 9.5, start.lon)), 50.0)
        assertEquals("광화문 방면으로 우회전.", now)
        t.advance(2000.0)
        assertTrue(a.next(route, t.progress(), 30.0)!!.startsWith("목적지 경복궁"))
        assertNull(a.next(route, t.progress(), 30.0))
    }

    @Test fun distanceWordsRoundSensibly() {
        assertEquals("1.5킬로미터", GuidanceAnnouncer.distanceWords(1480.0))
        assertEquals("2킬로미터", GuidanceAnnouncer.distanceWords(2010.0))
        assertEquals("300미터", GuidanceAnnouncer.distanceWords(296.0))
        assertEquals("80미터", GuidanceAnnouncer.distanceWords(78.0))
    }

    @Test fun maneuverFromCodeOrWording() {
        assertEquals(Maneuver.RIGHT, Maneuver.of(3, ""))
        assertEquals(Maneuver.LEFT, Maneuver.of(8, "비보호 좌회전"))
        assertEquals(Maneuver.DESTINATION, Maneuver.of(88, ""))
        assertEquals(Maneuver.EXIT, Maneuver.of(60, "'서초IC' 출구로 진출"))
        assertEquals(Maneuver.TOLL, Maneuver.of(121, ""))
        assertEquals(Maneuver.KEEP_RIGHT, Maneuver.of(70, "오른쪽 방향으로 이동"))
    }

    @Test fun energyCurveGrowsWithSpeedAndCold() {
        assertTrue(EnergyModel.whPerKm(120.0) > EnergyModel.whPerKm(80.0))
        assertTrue(EnergyModel.whPerKm(100.0, -5.0) > EnergyModel.whPerKm(100.0, 20.0))
        assertEquals(115.0, EnergyModel.whPerKm(50.0), 0.01)
    }

    @Test fun arrivalBatteryUsesSectionSpeeds() {
        val t = RouteTracker(route)
        val kwh = EnergyModel.kwhToGo(t, 0.0, 1.0, 20.0)
        // 1 km at 50 km/h (115 Wh) + 1 km at 12 km/h (~140 Wh).
        assertEquals(0.255, kwh, 0.01)
        val soc = EnergyModel.socAtArrival(socNow = 50.0, remainingKwh = null, fullKwh = 75.0, needKwh = 7.5)
        assertEquals(40.0, soc!!, 0.01)
        assertNull(EnergyModel.socAtArrival(null, null, null, 1.0))
    }

    @Test fun learnerScalesTheCurveAfterTwoKilometres() {
        val l = ConsumptionLearner()
        repeat(60) { l.observe(powerKw = 21.6, speedKmh = 90.0, seconds = 1.0, outsideC = 20.0) } // 1.5 km
        assertEquals(1.0, l.factor)
        repeat(60) { l.observe(21.6, 90.0, 1.0, 20.0) }
        // 21.6 kW at 90 km/h is 240 Wh/km against the curve's 145.
        assertEquals(240.0 / 145.0, l.factor, 0.01)
    }

    @Test fun plannerPrefersSuperchargersNearTheLineThatCanBeReached() {
        val longPath = (0..200).map { LatLon(start.lat + it * 0.009044, start.lon) } // 200 km north
        val long = route.copy(path = longPath, sections = emptyList(), guides = emptyList(), distanceM = 200_000, durationMs = 7_200_000)
        val t = RouteTracker(long)
        val spots = listOf(
            ChargerSpot("공용 급속", longPath[120].let { LatLon(it.lat, it.lon + 0.005) }, tesla = false, powerKw = 200, available = 2, total = 4),
            ChargerSpot("슈퍼차저", longPath[100].let { LatLon(it.lat, it.lon + 0.01) }, tesla = true, powerKw = 250, available = 3, total = 8),
            ChargerSpot("만차", longPath[110], tesla = true, powerKw = 250, available = 0, total = 8),
            ChargerSpot("멀리", LatLon(longPath[90].lat, longPath[90].lon + 0.2), tesla = true, powerKw = 250, available = 5, total = 8),
            ChargerSpot("못 감", longPath[190], tesla = true, powerKw = 250, available = 5, total = 8),
        )
        val stops = ChargePlanner.plan(t, 0.0, spots, socNow = 45.0, fullKwh = 75.0, factor = 1.0, outsideC = 20.0)
        assertEquals(listOf("슈퍼차저", "공용 급속"), stops.map { it.spot.name })
        assertNotNull(stops.first().socOnArrival)
        assertTrue(stops.first().socOnArrival!! > 7.0)
    }

    @Test fun camerasOnTheRouteOnlyAndInOrder() {
        val cams = listOf(
            SpeedCamera("a", path[15].lat, path[15].lon + 0.0001, 50, "사직로"),
            SpeedCamera("b", path[5].lat, path[5].lon, 50, "세종대로"),
            SpeedCamera("off", path[8].lat, path[8].lon + 0.003, 60, "옆길"),
        )
        val on = RouteCameras.along(RouteGeometry(path), CameraIndex(cams))
        assertEquals(listOf("b", "a"), on.map { it.camera.id })
        assertEquals("a", RouteCameras.next(on, 700.0)!!.camera.id)
    }
}
