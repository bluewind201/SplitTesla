package com.splittesla.core.qa.q1

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.NaviPlace
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteGuide
import com.splittesla.core.navi.RouteOption
import com.splittesla.core.navi.RouteProgress
import com.splittesla.core.navi.RouteTracker
import com.splittesla.core.voice.SpeechQueue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q1 acceptance (PM task_52216cdb83df), fixed BEFORE the implementation exists. Only [TurnNotices]
 * is bound to the frozen API afterwards; scenarios and expected values must not change to fit it.
 * This is a JVM simulation of queue timing and 1 Hz fixes, not NaviHub and not a device.
 */
interface TurnNotices {
    /** A sentence due on this progress, or null. Called once per fix. */
    fun next(route: NaviRoute, p: RouteProgress, kmh: Double): String?
    /** Set before [next]; lets an adapter hand a playback API its clock. */
    fun tick(nowMs: Long) = Unit
    /** Identifies the attempt [next] just returned, for later outcome reports. */
    fun token(): Any? = null
    fun started(token: Any?) = Unit
    fun completed(token: Any?) = Unit
    /** That sentence was never fully heard. */
    fun discarded(guide: Int?, token: Any?)
    fun reset()
}

data class Heard(val atMs: Long, val text: String, val distanceM: Double?, val guide: Int?)
data class Camera(val atMs: Long, val durationMs: Long, val priority: Int = 100, val text: String = "카메라")

class Sim(private val notices: TurnNotices, private val kmh: Double, private val startM: Double = 1100.0, private val turnSpeechMs: Long = 2000,
          private val cameras: List<Camera> = emptyList(), private val accuracyAt: (Long) -> Double = { 5.0 },
          private val deadReckonFromMs: Long? = null, private val changeRouteAtMs: Long? = null) {
    private class Ctx(val guide: Int?, val version: Int, val along: Double, val at: Long, val token: Any?)
    private val q = SpeechQueue()
    private val v = kmh / 3.6
    private var route = line("우회전", 2000)
    private var tracker = RouteTracker(route)
    private var version = 1
    private var p: RouteProgress = tracker.progress()
    private val pending = LinkedHashMap<String, Ctx>()
    private var activeEnd = -1L
    private var activeKey: String? = null
    private var seq = 0
    val heard = ArrayList<Heard>()
    val cameraHeard = ArrayList<Long>()
    val cameraEnds = ArrayList<Long>()
    var cameraCuts = 0
    var cut = 0

    private fun drop(key: String) { pending.remove(key)?.let { notices.discarded(it.guide, it.token) } }
    private fun valid(c: Ctx, t: Long) = c.version == version && p.canAnnounceTurn && c.guide == p.nextGuideIndex &&
        t - c.at <= 1500 && abs(c.along - p.alongM) <= 10

    fun run(untilMs: Long = 70_000): Sim {
        var t = 0L
        while (t <= untilMs) {
            clock = t
            if (t == changeRouteAtMs) {
                route = line("좌회전", 2300); tracker = RouteTracker(route); version++; notices.reset()
            }
            if (t % 1000 == 0L) fix(t)
            cameras.filter { it.atMs == t }.forEach { c ->
                val r = SpeechQueue.Request(1, c.text, "cam:$t", c.priority, t + 6000, deduplicate = false)
                val offered = q.offer(r, t)
                if (offered && q.shouldPreempt(r)) {
                    val active = activeKey
                    if (active != null && pending.containsKey(active)) { cut++; q.finish(); activeKey = null; activeEnd = -1; drop(active) }
                    else if (active != null) { cameraCuts++; q.finish(); activeKey = null; activeEnd = -1 }
                }
            }
            if (activeEnd in 0..t) {
                q.finish(); activeEnd = -1
                activeKey?.let { k -> pending.remove(k)?.let { notices.completed(it.token) } }; activeKey = null
            }
            pending.entries.filter { it.key != activeKey && t - it.value.at >= 1500 }.map { it.key }.forEach { drop(it) }
            val next = q.next(t)
            if (next != null) {
                activeKey = next.key
                val c = pending[next.key]
                if (c == null) {
                    val cam = cameras.first { "cam:${it.atMs}" == next.key }
                    activeEnd = t + cam.durationMs; cameraHeard += t; cameraEnds += activeEnd
                } else {
                    activeEnd = t + turnSpeechMs
                    notices.started(c.token)
                    heard += Heard(t, next.text, p.nextGuideDistanceM, c.guide)
                }
            }
            t += 100
        }
        return this
    }

    private fun fix(t: Long) {
        val pos = startM + v * t / 1000.0
        val dr = deadReckonFromMs
        p = if (dr != null && t >= dr) tracker.advance(v, 1000) else tracker.update(on(pos), accuracyAt(t), t)
        if (route.guides.size < 2) return
        notices.tick(t)
        val text = notices.next(route, p, kmh) ?: return
        val key = "turn:${seq++}"
        val c = Ctx(p.nextGuideIndex, version, p.alongM, t, notices.token())
        pending[key] = c
        val me = key
        val r = SpeechQueue.Request(2, text, key, 70, t + 1500, "turn", deduplicate = false,
            validAtStart = { val ok = valid(c, clock); if (!ok) drop(me); ok })
        if (!q.offer(r, t)) drop(key)
    }
    private var clock = 0L

    companion object {
        val start = LatLon(37.5663, 126.9779)
        fun on(m: Double) = LatLon(start.lat + m / 110574.0, start.lon)
        fun line(word: String, turnAtM: Int): NaviRoute {
            val path = (0..35).map { on(it * 100.0) }
            return NaviRoute(RouteOption.FAST, path, emptyList(),
                listOf(RouteGuide(turnAtM / 100, 3, word, turnAtM, 200_000), RouteGuide(35, 88, "목적지", 3500 - turnAtM, 60_000)),
                3500, 260_000, 0, NaviPlace("목적지", path.last()))
        }
    }
}

abstract class Q1Scenarios {
    abstract fun notices(): TurnNotices
    @BeforeEach fun gate() = assumeTrue(System.getenv("QA_REPRO") == "1", "QA_REPRO=1 to run Q1 acceptance")

    /** Seconds until the fix on which [stageM] is first reached, for a turn at 2000 m from 1100 m. */
    private fun stageFixMs(kmh: Double, stageM: Double) = ceil((900 - stageM) / (kmh / 3.6)).toLong() * 1000
    private fun spokenMetres(text: String) = Regex("(\\d+)미터 앞").find(text)?.groupValues?.get(1)?.toDouble()
    private fun assertCurrentDistance(h: Heard) {
        val said = spokenMetres(h.text) ?: return
        val d = h.distanceM!!
        val tolerance = if (d >= 100) 60.0 else 15.0 // rounding plus the 10 m validity window
        assertTrue(abs(said - d) <= tolerance, "said $said m at $d m: ${h.text}")
    }

    @Test fun s1_cutLastStageTurnIsAnnouncedAgainWhileAheadAndOnlyOnce() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, cameras = listOf(Camera(fix + 300, 2000))).run()
        assertEquals(1, s.cut)
        val after = s.heard.filter { it.atMs > fix + 300 && it.guide == 0 }
        assertEquals(1, after.size, "${s.heard}")
        assertTrue(after.single().distanceM!! > 0)
        assertTrue(after.single().atMs >= s.cameraEnds.single(), "final camera goes first")
        assertCurrentDistance(after.single())
    }

    @Test fun s2_repeatedDiscardAtThirtySixKmhAndAboveStillGivesOneCurrentNotice() {
        for ((kmh, stage) in listOf(36.0 to 300.0, 60.0 to 300.0, 100.0 to 500.0)) {
            val fix = stageFixMs(kmh, stage)
            // Two approach sentences hold the queue well past the validity window.
            val s = Sim(notices(), kmh, cameras = listOf(Camera(fix - 500, 4000, 60), Camera(fix + 3500, 4000, 60))).run()
            val busyUntil = s.cameraEnds.max()
            val after = s.heard.filter { it.atMs >= fix - 500 && it.atMs < busyUntil + 3000 && it.guide == 0 }
            assertEquals(1, after.size, "$kmh km/h ${s.heard}")
            assertCurrentDistance(after.single())
            assertTrue(s.heard.none { it.atMs >= busyUntil && spokenMetres(it.text) == stage }, "old sentence replayed at $kmh: ${s.heard}")
        }
    }

    @Test fun s3_noNoticeOnceTheTurnIsPassed() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, turnSpeechMs = 4000, cameras = listOf(Camera(fix + 3500, 5000))).run()
        assertEquals(1, s.cut)
        assertTrue(s.heard.none { it.atMs > fix + 3500 && it.text.contains("우회전") }, "${s.heard}")
    }

    @Test fun s4_noOldNoticeAfterARouteChange() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, cameras = listOf(Camera(fix + 300, 2000)), changeRouteAtMs = fix + 1000).run()
        assertTrue(s.heard.none { it.atMs > fix + 300 && it.text.contains("우회전") }, "${s.heard}")
    }

    @Test fun s5_noNoticeWhilePositionIsUncertain() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, cameras = listOf(Camera(fix + 300, 2000)), accuracyAt = { if (it > fix) 50.0 else 5.0 }).run()
        assertTrue(s.heard.none { it.atMs > fix + 300 && it.guide == 0 }, "${s.heard}")
    }

    @Test fun s6_noticeReturnsWhenPositionRecoversAndTheTurnIsStillAhead() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, cameras = listOf(Camera(fix + 300, 1500)), accuracyAt = { if (it in (fix + 1)..(fix + 2000)) 50.0 else 5.0 }).run()
        val after = s.heard.filter { it.atMs > fix + 300 && it.guide == 0 }
        assertEquals(1, after.size, "${s.heard}")
        assertTrue(after.single().atMs >= fix + 3000, "not before position is good again")
    }

    @Test fun s7_finalCameraIsNeverCutOrDelayedByTurnNotices() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, cameras = listOf(Camera(fix + 300, 2000), Camera(fix + 2300, 2000))).run()
        assertEquals(0, s.cameraCuts)
        assertEquals(2, s.cameraHeard.size)
        s.cameraHeard.zip(s.cameraEnds).forEach { (a, b) -> assertTrue(s.heard.none { it.atMs in a until b }, "${s.heard}") }
    }

    @Test fun s8_undisturbedDriveHasNoExtraNoticesEvenWithLongSentences() {
        for (ms in listOf(2000L, 4000L)) {
            val s = Sim(notices(), 60.0, turnSpeechMs = ms).run()
            assertEquals(listOf("700미터 앞, 우회전.", "300미터 앞, 우회전.", "우회전."), s.heard.filter { it.guide == 0 }.map { it.text })
        }
    }

    @Test fun s9_estimatedNoticeReturnsLabelledInsideTheWindow() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, cameras = listOf(Camera(fix + 300, 2000)), deadReckonFromMs = fix - 3000).run()
        val after = s.heard.filter { it.atMs > fix + 300 && it.guide == 0 }
        assertEquals(1, after.size, "${s.heard}")
        assertTrue(after.single().text.startsWith("추정 위치 기준"), after.single().text)
    }

    @Test fun s10_noNoticeOnceTheEstimateHasExpired() {
        val fix = stageFixMs(60.0, 80.0)
        val s = Sim(notices(), 60.0, cameras = listOf(Camera(fix + 300, 2000)), deadReckonFromMs = fix - 31_000).run()
        assertTrue(s.heard.none { it.atMs > fix + 300 && it.guide == 0 }, "${s.heard}")
    }
}

/** Today's announcer: no re-notice. Expected to fail the "notice returns" scenarios. */
class CurrentBehaviourQ1Test : Q1Scenarios() {
    override fun notices() = object : TurnNotices {
        val a = GuidanceAnnouncer()
        override fun next(route: NaviRoute, p: RouteProgress, kmh: Double) = a.next(route, p, kmh)
        override fun discarded(guide: Int?, token: Any?) = Unit
        override fun reset() = a.reset()
    }
}

/** QA's reference policy: proves the scenarios can be satisfied together. Not a proposed design. */
class ReferencePolicyQ1Test : Q1Scenarios() {
    override fun notices() = object : TurnNotices {
        val a = GuidanceAnnouncer()
        var owed: Int? = null
        override fun next(route: NaviRoute, p: RouteProgress, kmh: Double): String? {
            a.next(route, p, kmh)?.let { owed = null; return it }
            val g = owed ?: return null
            if (p.nextGuideIndex != g) { owed = null; return null }
            if (!p.canAnnounceTurn) return null
            owed = null
            val d = p.nextGuideDistanceM ?: return null
            val text = "${GuidanceAnnouncer.distanceWords(d)} 앞, ${GuidanceAnnouncer.speakable(route.guides[g])}."
            return if (p.estimated) "추정 위치 기준, $text" else text
        }
        override fun discarded(guide: Int?, token: Any?) { owed = guide }
        override fun reset() { a.reset(); owed = null }
    }
}
