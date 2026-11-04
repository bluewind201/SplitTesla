package com.splittesla.core.qa.q1

import com.splittesla.core.navi.GuidanceAnnouncer
import com.splittesla.core.navi.NaviRoute
import com.splittesla.core.navi.RouteProgress
import com.splittesla.core.voice.SpeechOutcome

/** Binds the fixed Q1 scenarios to the playback API. Interrupted vs dropped follows the queue path. */
class FrozenApiQ1Test : Q1Scenarios() {
    override fun notices() = object : TurnNotices {
        val a = GuidanceAnnouncer()
        var now = 0L
        var last: GuidanceAnnouncer.PlaybackNotice? = null
        override fun tick(nowMs: Long) { now = nowMs }
        override fun next(route: NaviRoute, p: RouteProgress, kmh: Double): String? {
            last = a.nextForPlayback(route, p, kmh, now)
            return last?.text
        }
        override fun token(): Any? = last
        override fun started(token: Any?) = a.playback(token as GuidanceAnnouncer.PlaybackNotice, SpeechOutcome.STARTED)
        override fun completed(token: Any?) = a.playback(token as GuidanceAnnouncer.PlaybackNotice, SpeechOutcome.COMPLETED)
        override fun discarded(guide: Int?, token: Any?) = a.playback(token as GuidanceAnnouncer.PlaybackNotice, SpeechOutcome.DROPPED)
        override fun reset() = a.reset()
    }
}
