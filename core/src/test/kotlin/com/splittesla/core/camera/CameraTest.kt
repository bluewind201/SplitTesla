package com.splittesla.core.camera

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CameraTest {
    private val csv = """
        무인교통단속카메라관리번호,시도명,설치장소,위도,경도,단속구분,제한속도,단속구간위치구분,과속단속구간길이
        C1,서울특별시,"테헤란로, 역삼역 앞",37.5000,127.0000,1,60,,
        C2,서울특별시,신호전용,37.5100,127.0000,2,,,
        C3,경기도,구간 시작,37.6000,127.0000,1,100,구간시작,3000
        C4,경기도,구간 끝,37.6270,127.0000,1,100,구간종료,3000
    """.trimIndent()

    @Test
    fun parsesCp949CsvAndSkipsCamerasWithoutLimit() {
        val cams = CameraCsv.parse(csv.toByteArray(Charset.forName("MS949")))
        assertEquals(listOf("C1", "C3", "C4"), cams.map { it.id })
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
}
