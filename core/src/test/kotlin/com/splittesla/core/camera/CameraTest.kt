package com.splittesla.core.camera

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CameraTest {
    private val csv = """
        무인교통단속카메라관리번호,시도명,설치장소,위도,경도,단속구분,제한속도,단속구간위치구분,과속단속구간길이,보호구역구분
        C1,서울특별시,"테헤란로, 역삼역 앞",37.5000,127.0000,1,60,,,
        C2,서울특별시,신호전용,37.5100,127.0000,2,,,,
        C3,경기도,구간 시작,37.6000,127.0000,1,100,구간시작,3000,
        C4,경기도,구간 끝,37.6270,127.0000,1,100,구간종료,3000,
        C5,서울특별시,초등학교 앞,37.7000,127.0000,1,30,,,어린이보호구역
    """.trimIndent()

    @Test
    fun parsesCp949CsvAndSkipsCamerasWithoutLimit() {
        val cams = CameraCsv.parse(csv.toByteArray(Charset.forName("MS949")))
        assertEquals(listOf("C1", "C3", "C4", "C5"), cams.map { it.id })
        assertEquals("어린이보호구역", cams[3].zone)
        assertEquals(null, cams[0].zone)
        assertEquals("테헤란로, 역삼역 앞", cams[0].place)
        assertEquals(60, cams[0].limitKmh)
        assertEquals(SectionRole.START, cams[1].section)
        assertEquals(SectionRole.END, cams[2].section)
        assertEquals(cams, CameraStore.decode(CameraStore.encode(cams)))
    }

    /** Drives north along longitude 127.0 at [kmh], one fix per second. */
    private fun drive(
        alerter: CameraAlerter,
        fromLat: Double,
        toLat: Double,
        kmh: Double,
        startMs: Long = 0,
        roadLimitKmh: Int? = null,
    ): List<Pair<Double, CameraAlert>> {
        val stepDeg = kmh / 3.6 / 111_195.0
        val out = ArrayList<Pair<Double, CameraAlert>>()
        var lat = fromLat
        var t = startMs
        while (lat < toLat) {
            alerter.update(lat, 127.0, 0.0, kmh, t, roadLimitKmh).forEach { out += lat to it }
            lat += stepDeg
            t += 1000
        }
        return out
    }

    @Test
    fun warnsEarlyByspeedThenAt100m() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        val alerts = drive(alerter, 37.49, 37.505, 90.0)
        val early = alerts.first { it.second is CameraAlert.Approaching }
        val final = alerts.first { it.second is CameraAlert.Final }
        // At 90 km/h the early warning comes ~300 m out (12 s of travel).
        assertTrue(early.second.distanceM in 280.0..320.0, "early at ${early.second.distanceM}")
        assertTrue(final.second.distanceM in 75.0..100.0, "final at ${final.second.distanceM}")
        assertTrue((final.second as CameraAlert.Final).overLimit)
        assertEquals(2, alerts.count { it.second.camera.id == "C1" }, "each stage warns once")
    }

    @Test
    fun ignoresCamerasBehindOrOffToTheSide() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        // Driving south, away from C1 which is 200 m behind.
        val alerts = alerter.update(37.4982, 127.0, 180.0, 60.0, 0)
        assertTrue(alerts.isEmpty())
        // Driving east past it: C1 is 200 m to the north, outside the cone.
        assertTrue(alerter.update(37.4982, 127.0, 90.0, 60.0, 1000).isEmpty())
    }

    @Test
    fun measuresSectionAverageSpeed() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        val alerts = drive(alerter, 37.595, 37.63, 108.0).map { it.second }
        assertNotNull(alerts.filterIsInstance<CameraAlert.SectionStart>().singleOrNull())
        val end = alerts.filterIsInstance<CameraAlert.SectionEnd>().single()
        assertEquals(108.0, end.averageKmh, 3.0)
        assertIs<CameraAlert.Final>(alerts.last { it.camera.id == "C4" && it !is CameraAlert.SectionEnd })
    }

    @Test
    fun ignoresParallelStreetCameraThatAConeWouldCatch() {
        // A camera 120 m east of our road, 450 m ahead: inside a 30° cone, but on another street.
        val side = SpeedCamera("S", 37.5040, 127.00136, 50, "옆 도로")
        val alerter = CameraAlerter(CameraIndex(listOf(side)))
        val alerts = drive(alerter, 37.499, 37.505, 72.0)
        assertTrue(alerts.isEmpty(), "got $alerts")
    }

    @Test
    fun ignoresCameraWeAreMovingAwayFrom() {
        val cam = SpeedCamera("A", 37.5000, 127.0000, 60, "")
        val alerter = CameraAlerter(CameraIndex(listOf(cam)))
        // GPS course claims north, but the fixes move south (bad course at a junction).
        var t = 0L
        var lat = 37.4985
        val alerts = ArrayList<CameraAlert>()
        repeat(5) {
            alerts += alerter.update(lat, 127.0, 0.0, 60.0, t)
            lat -= 0.0002
            t += 1000
        }
        assertTrue(alerts.isEmpty(), "got $alerts")
    }

    @Test
    fun reportsSectionRemainingAndSafeSpeed() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        drive(alerter, 37.595, 37.6135, 108.0)
        val s = alerter.status
        val remaining = assertNotNull(s.sectionRemainingM)
        assertEquals(1500.0, remaining, 100.0)
        // Half way at 108 km/h on a 100 km/h section: the rest must be driven slower than 100.
        val safe = assertNotNull(s.sectionSafeKmh)
        assertTrue(safe in 85.0..97.0, "safe $safe")
    }

    @Test
    fun safeSpeedMath() {
        // 10 km at 100 km/h = 360 s budget; 5 km done in 150 s leaves 210 s for 5 km.
        assertEquals(5000 / 210.0 * 3.6, CameraAlerter.sectionSafeKmh(10_000.0, 100, 5000.0, 150.0)!!, 0.01)
        assertEquals(null, CameraAlerter.sectionSafeKmh(10_000.0, 100, 5000.0, 400.0))
    }

    private val metresPerDegLon = 111_195.0 * Math.cos(Math.toRadians(37.5))

    @Test
    fun warnsForJunctionPointsOnEitherSide() {
        // The dataset often puts a camera at the middle of a junction, left or right of our lane.
        for (offset in listOf(-20.0, 8.0)) {
            val cam = SpeedCamera("J", 37.5000, 127.0 + offset / metresPerDegLon, 80, "교차로")
            val alerts = drive(CameraAlerter(CameraIndex(listOf(cam))), 37.49, 37.505, 90.0).map { it.second }
            assertTrue(alerts.any { it is CameraAlert.Approaching } && alerts.any { it is CameraAlert.Final }, "offset $offset got $alerts")
        }
    }

    @Test
    fun skipsCameraTheDriverMarkedForThisDirection() {
        val cam = SpeedCamera("X", 37.5000, 127.0, 80, "반대편 전용")
        // Marked while heading north (10°): skipped northbound, still warned southbound.
        val ignored = { id: String, heading: Double -> id == "X" && Geo.angleDiff(heading, 10.0) <= 45 }
        assertTrue(drive(CameraAlerter(CameraIndex(listOf(cam)), ignored = ignored), 37.49, 37.505, 90.0).isEmpty())
        val south = CameraAlerter(CameraIndex(listOf(cam)), ignored = ignored)
        val alerts = ArrayList<CameraAlert>()
        var lat = 37.51
        var t = 0L
        while (lat > 37.499) {
            alerts += south.update(lat, 127.0, 180.0, 90.0, t)
            lat -= 25 / 111_195.0
            t += 1000
        }
        assertTrue(alerts.any { it is CameraAlert.Final }, "got $alerts")
    }

    @Test
    fun followsALongLeftBend() {
        // 800 m radius left-hand bend; the camera stands 6 m outside it (our right), 480 m round.
        val r = 800.0
        fun at(theta: Double, radius: Double = r) =
            (37.5 + radius * Math.sin(theta) / 111_195.0) to (127.0 + (radius * Math.cos(theta) - r) / metresPerDegLon)
        val (cLat, cLon) = at(0.6, r + 6)
        val alerter = CameraAlerter(CameraIndex(listOf(SpeedCamera("B", cLat, cLon, 80, "커브"))))
        val alerts = ArrayList<CameraAlert>()
        var theta = 0.0
        var t = 0L
        while (theta < 0.65) {
            val (lat, lon) = at(theta)
            val heading = (Math.toDegrees(Math.atan2(-Math.sin(theta), Math.cos(theta))) + 360) % 360
            alerts += alerter.update(lat, lon, heading, 72.0, t)
            theta += 20 / r
            t += 1000
        }
        val early = alerts.filterIsInstance<CameraAlert.Approaching>().singleOrNull()
        assertNotNull(early, "got $alerts")
        assertTrue(early.distanceM > 180, "early at ${early.distanceM}")
        assertTrue(alerts.any { it is CameraAlert.Final })
    }

    @Test
    fun readsTheRealDatasetCodes() {
        // Shaped like the data.go.kr sample: one record per direction at one point, numeric codes.
        val cams = CameraCsv.parse(
            """
            무인교통단속카메라관리번호,설치장소,위도,경도,단속구분,제한속도,도로노선방향,보호구역구분,단속구간위치구분
            1,하귀교차로(여주시청→가남읍),37.2285839,127.5648913,2,70,3,,
            2,하귀교차로(가남읍→여주시청),37.2285839,127.5648913,2,70,3,,
            3,우만통 입구 노인보호구역(여주시청→흔암리),37.25597796,127.6758508,1,30,3,1,
            4,초등학교 앞,37.3,127.6,1,30,3,2,
            5,작전동 주정차,37.53075196,126.7370527,3,0,1,,
            6,버스전용차로,37.54,126.74,4,50,3,,
            7,구월중학교직전(구간단속 시점),37.45,126.70,1,60,3,,
            8,석천초등학교(구간단속 종점),37.46,126.70,1,60,3,,
            9,이동식 단속지점,37.47,126.70,1,60,3,,
            10,중앙사거리 신호·과속 겸용,37.48,126.70,01+02,50,3,,
            11,시청앞 사거리,37.49,126.70,1+2,60,3,,
            12,기타 단속,37.50,126.70,99,50,3,,
            """.trimIndent(),
        )
        // Speed-and-signal cameras ("1+2") are real fixed cameras; 99 (other) and mobile entries are not.
        assertEquals(listOf("1", "3", "4", "7", "8", "10", "11"), cams.map { it.id })
        assertTrue(cams.first { it.id == "10" }.signal)
        assertTrue(cams[0].signal)
        assertEquals("노인보호구역", cams[1].zone)
        assertEquals("어린이보호구역", cams[2].zone)
        assertEquals(SectionRole.START, cams[3].section)
        assertEquals(SectionRole.END, cams[4].section)
        assertEquals(cams, CameraStore.decode(CameraStore.encode(cams)))
    }

    @Test
    fun whileGuidingOnlyCamerasOnTheRouteAreAnnounced() {
        val cams = CameraIndex(CameraCsv.parse(csv))
        val elsewhere = CameraAlerter(cams).apply { routeCameraIds = setOf("C3") }
        assertTrue(drive(elsewhere, 37.49, 37.505, 90.0).isEmpty())
        val onRoute = CameraAlerter(cams).apply { routeCameraIds = setOf("C1") }
        assertTrue(drive(onRoute, 37.49, 37.505, 90.0).any { it.second is CameraAlert.Final })
    }

    @Test
    fun carMapLimitFarFromTheCamerasMeansAnotherRoad() {
        val cams = CameraIndex(CameraCsv.parse(csv))
        // On a 100 road (an expressway above), C1's 60 belongs to the street below.
        assertTrue(drive(CameraAlerter(cams), 37.49, 37.505, 90.0, roadLimitKmh = 100).isEmpty())
        // 70 against 60 is ordinary map disagreement: still warned.
        assertTrue(drive(CameraAlerter(cams), 37.49, 37.505, 90.0, roadLimitKmh = 70).any { it.second is CameraAlert.Final })
        // A school zone camera is kept whatever the car's map says.
        val zone = drive(CameraAlerter(cams), 37.69, 37.705, 50.0, roadLimitKmh = 70)
        assertTrue(zone.any { it.second.camera.id == "C5" && it.second is CameraAlert.Final })
    }

    @Test
    fun cameraThatSlidesOffToTheSideIsReportedAsMissed() {
        // 40 m east of our road, on a parallel street: announced from afar, then it drops away.
        val side = SpeedCamera("P", 37.5000, 127.0 + 40 / metresPerDegLon, 60, "옆길")
        val missed = ArrayList<Double>()
        val passed = ArrayList<String>()
        val alerter = CameraAlerter(CameraIndex(listOf(side)), onMiss = { _, h -> missed += h }, onDrivenPast = { c, _ -> passed += c.id })
        val alerts = drive(alerter, 37.49, 37.505, 72.0)
        assertTrue(alerts.any { it.second is CameraAlert.Approaching }, "got $alerts")
        assertEquals(1, missed.size)
        assertEquals(0.0, missed.single(), 1.0)
        assertTrue(passed.isEmpty())
    }

    @Test
    fun cameraDrivenRightPastIsConfirmedNotMissed() {
        val missed = ArrayList<String>()
        val passed = ArrayList<String>()
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)), onMiss = { c, _ -> missed += c.id }, onDrivenPast = { c, _ -> passed += c.id })
        drive(alerter, 37.49, 37.505, 90.0)
        assertEquals(listOf("C1"), passed)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun turningOffBeforeTheCameraSaysNothingAboutIt() {
        val missed = ArrayList<String>()
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)), onMiss = { c, _ -> missed += c.id })
        // North towards C1 until ~200 m short of it, then east along a side street.
        drive(alerter, 37.49, 37.4982, 72.0)
        var lon = 127.0
        var t = 100_000L
        repeat(15) {
            alerter.update(37.4982, lon, 90.0, 72.0, t)
            lon += 20 / metresPerDegLon
            t += 1000
        }
        assertTrue(missed.isEmpty(), "got $missed")
    }
}
