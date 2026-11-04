package com.splittesla.core.qa

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.camera.CameraAlert
import com.splittesla.core.camera.CameraAlerter
import com.splittesla.core.camera.CameraCsv
import com.splittesla.core.camera.CameraIndex
import com.splittesla.core.navi.FixGate
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteTracker
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Defect reproductions (QA-D*, docs/android-guidance/qa.md). Each test asserts the
 * EXPECTED safe behaviour and FAILS on the current implementation. Skipped in a
 * normal run so the suite stays green; run with `QA_REPRO=1` to reproduce.
 * A skipped or failed test here is never counted as a pass.
 */
class AndroidGuidanceQaDefectsTest {
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to reproduce defects")

    private fun north(m: Double) = LatLon(37.5 + m / 110574.0, 127.0)

    /** QA-D1: fixes arriving every 6 s (> 5 s budget, > 2.5 s candidate window) are rejected forever. */
    @Test fun d1_fixesSixSecondsApartAreNotLockedOutForever() {
        val g = FixGate()
        val v = 80 / 3.6
        var accepted = 0
        for (i in 0..30) if (g.accept(north(i * 6 * v), i * 6000L, 5.0) != null) accepted++
        assertTrue(accepted > 10, "accepted only $accepted of 31 fixes over 3 minutes")
    }

    /** QA-D2: at 2 s per fix and 100 km/h the last fix before a START camera is > 40 m away, so no SectionStart. */
    @Test fun d2_sectionStartIsAnnouncedWithTwoSecondFixes() {
        val csv = """
            무인교통단속카메라관리번호,시도명,설치장소,위도,경도,단속구분,제한속도,단속구간위치구분,과속단속구간길이,보호구역구분
            C3,경기도,구간 시작,37.6000,127.0000,1,100,구간시작,3000,
        """.trimIndent()
        var missed = 0
        val offsets = listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0)
        for (off in offsets) {
            val a = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
            val v = 100 / 3.6 * 2
            var lat = 37.596 + off / 111195.0
            var t = 0L
            val alerts = ArrayList<CameraAlert>()
            while (lat < 37.602) {
                alerts += a.update(lat, 127.0, 0.0, 100.0, t)
                lat += v / 111195.0
                t += 2000
            }
            if (alerts.none { it is CameraAlert.SectionStart }) missed++
        }
        assertEquals(0, missed, "SectionStart missed in $missed of ${offsets.size} phase offsets")
    }

    /** QA-D3: after the END camera of an unverified section is passed, status still claims the section. */
    @Test fun d3_passingTheEndCameraClearsUnverifiedSectionState() {
        val csv = """
            무인교통단속카메라관리번호,시도명,설치장소,위도,경도,단속구분,제한속도,단속구간위치구분,과속단속구간길이,보호구역구분
            C3,경기도,구간 시작,37.6000,127.0000,1,100,구간시작,3000,
            C4,경기도,구간 끝,37.6270,127.0000,1,100,구간종료,3000,
        """.trimIndent()
        val a = CameraAlerter(CameraIndex(CameraCsv.parse(csv)))
        val step = 100 / 3.6 / 111195.0
        var lat = 37.595
        var t = 0L
        var ended = false
        while (lat < 37.6295) {
            if (a.update(lat, 127.0, 0.0, 100.0, t).any { it is CameraAlert.SectionEnd }) ended = true
            lat += step
            t += 1000
        }
        assertTrue(ended)
        assertNull(a.status.sectionLimitKmh, "200 m past the END camera status still says section limit ${a.status.sectionLimitKmh}")
    }

    // ── Route ──
    private val start = LatLon(37.5663, 126.9779)
    private val dLat = 0.0009044
    private val kx = 111320.0 * Math.cos(Math.toRadians(37.5663))
    private fun on(m: Double, eastM: Double = 0.0) = LatLon(start.lat + m / 110574.0, start.lon + eastM / kx)
    private val out = (0..20).map { LatLon(start.lat + it * dLat, start.lon) }
    private fun route(path: List<LatLon>) = NaviRoute(
        option = RouteOption.FAST, path = path, sections = emptyList(), guides = emptyList(),
        distanceM = 0, durationMs = 0, tollFare = 0, goal = NaviPlace("목적지", path.last()),
    )

    /** QA-D4: after a tunnel, a 6 m-biased fix beside a parallel return leg snaps to that later leg. */
    @Test fun d4_tunnelExitDoesNotSnapToAParallelReturnLeg() {
        val back = out.reversed().map { it.copy(lon = it.lon + 10.0 / kx) } // return leg 10 m east
        val t = RouteTracker(route(out + back))
        val v = 60 / 3.6
        for (i in 0..30) t.update(on(i * v), 5.0, i * 1000L) // ~500 m
        for (k in 1..20) t.advance(v) // 20 s tunnel
        var maxAlong = 0.0
        for (k in 0..15) {
            val p = t.update(on(500 + 20 * v + k * v, eastM = 6.0), 8.0, 51_000L + k * 1000)
            maxAlong = maxOf(maxAlong, p.alongM)
        }
        assertTrue(maxAlong < 1500, "progress jumped to the return leg: along=$maxAlong (real ≈ ${500 + 35 * v})")
    }

    /** QA-D5: a real fix rejected by the projection check clears `estimated` but keeps the DR position. */
    @Test fun d5_rejectedProjectionDoesNotRelabelDeadReckoningAsReal() {
        val t = RouteTracker(route(out))
        val v = 60 / 3.6
        for (i in 0..10) t.update(on(i * v), 5.0, i * 1000L)
        for (k in 1..25) t.advance(v) // 25 s tunnel → along ≈ 583 m
        val dr = t.progress().alongM
        val relabelled = ArrayList<Int>()
        for (k in 0..10) {
            val p = t.update(on(166 + (26 + k) * v), 5.0, 36_000L + k * 1000)
            if (!p.estimated && p.alongM == dr) relabelled += k
        }
        assertTrue(relabelled.isEmpty(), "along stayed at the DR estimate $dr but estimated=false at fixes $relabelled")
    }

    /** QA-D7: driving through the end at 50 km/h speaks "안내를 마칩니다" yet guidance keeps going. */
    @Test fun d7_passThroughDoesNotSpeakEndOfGuidanceThatIsLaterRevoked() {
        val t = RouteTracker(route(out))
        val a = GuidanceAnnouncer()
        val r = route(out)
        val v = 50 / 3.6
        val spoken = ArrayList<String>()
        var i = 0
        var last = t.progress()
        while (i * v < 2100) {
            last = t.update(on(i * v), 5.0, i * 1000L)
            a.next(r, last, 50.0)?.let { spoken += it }
            i++
        }
        val endSaid = spoken.any { it.contains("안내를 마칩니다") }
        assertTrue(!endSaid || last.arrived, "said $spoken but arrived=${last.arrived} after driving on")
    }

    /** QA-D8: a bad first fix (cold start, 500 m off) blacks out real fixes for ~10 s. */
    @Test fun d8_badFirstFixDoesNotBlackOutTheNextTenSeconds() {
        val g = FixGate()
        g.accept(north(500.0), 0, 30.0)
        val v = 60 / 3.6
        var firstOk: Int? = null
        for (i in 1..20) if (g.accept(north(i * v), i * 1000L, 5.0) != null && firstOk == null) firstOk = i
        assertTrue((firstOk ?: 99) <= 3, "first real fix accepted after $firstOk s")
    }

    /** QA-D9: one spike followed by a 3 s dropout at 100 km/h freezes a too-small reach. */
    @Test fun d9_spikeThenShortDropoutRecoversQuickly() {
        val g = FixGate()
        val v = 100 / 3.6
        for (i in 0..5) g.accept(north(i * v), i * 1000L, 5.0)
        g.accept(north(5 * v + 400), 6000, 5.0) // spike
        var firstOk: Int? = null
        for (i in 9..25) if (g.accept(north(i * v), i * 1000L, 5.0) != null && firstOk == null) firstOk = i
        assertTrue((firstOk ?: 99) <= 10, "real track accepted only at t=$firstOk s (last good fix t=5)")
    }
}
