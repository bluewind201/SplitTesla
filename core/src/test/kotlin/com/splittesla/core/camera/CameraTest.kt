package com.splittesla.core.camera

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** Drives north along longitude 127.0 at [kmh], one fix every [stepMs]. */
    private fun drive(alerter: CameraAlerter, fromLat: Double, toLat: Double, kmh: Double, stepMs: Long = 1000, roadLimitKmh: Int? = null): List<Pair<Double, CameraAlert>> {
        val stepDeg = kmh / 3.6 * (stepMs / 1000.0) / 111_195.0
        val out = ArrayList<Pair<Double, CameraAlert>>()
        var lat = fromLat
        var t = 0L
        while (lat < toLat) {
            alerter.update(lat, 127.0, 0.0, kmh, t, roadLimitKmh).forEach { out += lat to it }
            lat += stepDeg
            t += stepMs
        }
        return out
    }

    @Test
    fun parsesCp949CsvAndSkipsCamerasWithoutLimit() {
        val cams = CameraCsv.parse(csv.toByteArray(Charset.forName("MS949")))
        assertEquals(listOf("C1", "C3", "C4", "C5"), cams.map { it.id })
        assertEquals("어린이보호구역", cams[3].zone)
        assertEquals("테헤란로, 역삼역 앞", cams[0].place)
        assertEquals(SectionRole.START, cams[1].section)
        assertEquals(SectionRole.END, cams[2].section)
        assertEquals(cams, CameraStore.decode(CameraStore.encode(cams)))
    }

    @Test
    fun warnsEarlyBySpeedThenAt100m() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        val alerts = drive(alerter, 37.49, 37.505, 90.0)
        val early = alerts.first { it.second is CameraAlert.Approaching }
        val d = (37.5 - early.first) * 111_195
        assertTrue(d in 250.0..320.0, "early at $d m")
        val fin = assertIs<CameraAlert.Final>(alerts.first { it.second is CameraAlert.Final }.second)
        assertTrue(fin.overLimit)
        assertEquals(1, alerts.count { it.second is CameraAlert.Final && it.second.camera.id == "C1" })
    }

    @Test
    fun statusShowsCameraAheadForTheGauge() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        drive(alerter, 37.497, 37.4985, 50.0)
        val s = alerter.status
        assertEquals("C1", s.ahead?.id)
        assertEquals(60, s.limitKmh)
        assertTrue(s.distanceM!! in 100.0..250.0)
        assertTrue(s.active)
        alerter.clear()
        assertFalse(alerter.status.active)
    }

    @Test
    fun sectionTracksAverageAndRemaining() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        val alerts = drive(alerter, 37.595, 37.615, 90.0).map { it.second }
        assertTrue(alerts.any { it is CameraAlert.SectionStart })
        val s = alerter.status
        assertEquals(100, s.sectionLimitKmh)
        assertTrue(s.sectionAverageKmh!! in 80.0..100.0, "avg ${s.sectionAverageKmh}")
        assertTrue(s.sectionRemainingM!! in 1000.0..2000.0, "remaining ${s.sectionRemainingM}")
        val end = drive(alerter, 37.615, 37.632, 90.0).map { it.second }.filterIsInstance<CameraAlert.SectionEnd>().single()
        assertNotNull(end.averageKmh)
    }

    @Test
    fun sparseCarGpsStillWarns() {
        // The car's GPS over BLE arrives every few seconds.
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        val alerts = drive(alerter, 37.49, 37.505, 60.0, stepMs = 3000)
        assertTrue(alerts.any { it.second is CameraAlert.Approaching || it.second is CameraAlert.Final })
    }

    @Test
    fun cameraOnAnotherRoadByLimitIsSkippedButZonesAreKept() {
        val alerter = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        assertTrue(drive(alerter, 37.49, 37.505, 90.0, roadLimitKmh = 100).none { it.second.camera.id == "C1" })
        val zone = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        assertTrue(drive(zone, 37.69, 37.701, 40.0, roadLimitKmh = 80).any { it.second.camera.id == "C5" })
    }

    @Test
    fun spokenAndScreenText() {
        val c = CameraCsv.parse(csv).first()
        assertTrue(CameraAlert.Final(c, 42.0, false).spokenText().startsWith("40미터 앞"))
        assertTrue(CameraAlert.Final(c, 42.0, true).spokenText().contains("속도를 줄이세요"))
        assertEquals("구간단속 종료", CameraAlert.SectionEnd(c, 0.0, null).spokenText())
        assertEquals("350m", cameraDistanceText(347.0))
        assertEquals("1.2km", cameraDistanceText(1234.0))
        assertEquals("어린이보호구역", CameraCsv.parse(csv).last().kindLabel)
    }

    @Test
    fun seedLoadsOffline() {
        val seed = CameraSeed.cameras
        assertTrue(seed.size >= 10)
        assertTrue(seed.any { it.section == SectionRole.START })
        assertTrue(seed.any { it.zone == "어린이보호구역" })
        assertTrue(seed.all { it.latitude in 33.0..39.0 && it.limitKmh in 30..110 })
    }
}
