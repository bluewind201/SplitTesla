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
    private fun drive(alerter: CameraAlerter, fromLat: Double, toLat: Double, kmh: Double, startMs: Long = 0): List<Pair<Double, CameraAlert>> {
        val stepDeg = kmh / 3.6 / 111_195.0
        val out = ArrayList<Pair<Double, CameraAlert>>()
        var lat = fromLat
        var t = startMs
        while (lat < toLat) {
            alerter.update(lat, 127.0, 0.0, kmh, t).forEach { out += lat to it }
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
}
