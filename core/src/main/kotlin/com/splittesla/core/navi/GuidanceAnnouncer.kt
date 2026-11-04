package com.splittesla.core.navi

import kotlin.math.roundToInt
import kotlin.math.abs
import com.splittesla.core.voice.SpeechOutcome

/**
 * Decides when to speak the next turn. Faster driving gets earlier and more
 * notices (highway: 2 km, 1 km, 500 m and at the turn; town: 700 m, 300 m and
 * at the turn). Each notice is said once per guide.
 */
class GuidanceAnnouncer {
    data class PlaybackNotice(val text: String, val guide: Int?, val stage: Int, val attempt: Long, val stepId: Int = stage, val thresholds: List<Double> = emptyList())
    private data class Pending(val notice: PlaybackNotice, val at: Long, val along: Double, var accepted: Boolean = false, var reserved: Boolean = false)
    private val pending = mutableMapOf<Pair<Int?, Int>, Pending>()
    private val owed = mutableSetOf<Pair<Int?, Int>>()
    private val completed = mutableSetOf<Pair<Int?, Int>>()
    // 0 = immediate turn for every speed tier; positive IDs are the advance distance in metres.
    private val entered = mutableMapOf<Int, Int>()
    private val finalTrigger = mutableMapOf<Int, Double>()
    private val issued = mutableSetOf<Pair<Int?, Int>>()
    private var attempts = 0L

    /** Playback phases move towards the turn and never regress when a speed tier changes. */
    @Synchronized fun nextForPlayback(route: NaviRoute, p: RouteProgress, speedKmh: Double?, now: Long): PlaybackNotice? {
        if (!p.canAnnounceTurn || p.offRoute) return null
        val stages = stagesFor(speedKmh ?: 0.0).toMutableList()
        val guide = if (p.arrived) null else p.nextGuideIndex ?: return null
        val distance = p.nextGuideDistanceM
        var stage = if (p.arrived) -1 else stages.indices.lastOrNull {
            distance != null && distance.isFinite() && distance > 0 && distance <= stages[it]
        } ?: return null
        if (guide != null && route.guides[guide].maneuver == Maneuver.DESTINATION) return null
        if (guide != null && entered[guide] == 0) {
            // Once immediate guidance was reached, slowing down cannot turn it back into an advance notice.
            val trigger = finalTrigger.getValue(guide)
            if (distance == null || distance > trigger) return null
            stages[stages.lastIndex] = maxOf(stages.last(), trigger)
            stage = stages.lastIndex
        }
        var stepId = if (p.arrived) -1 else if (stage == stages.lastIndex) 0 else stages[stage].toInt()
        val floor = guide?.let { entered[it] }
        if (guide != null && floor != null && stepId > floor) {
            // An unheard nearer advance phase stays current across a tier change. Completed
            // phases never move back outwards; a new, nearer phase may still supersede this one.
            if (floor <= 0 || ((guide to floor) !in pending && (guide to floor) !in owed) ||
                distance == null || distance > floor) return null
            val nearer = stages.filter { it < floor }
            stages.clear()
            stages += floor.toDouble()
            stages.addAll(nearer)
            stage = stages.indices.lastOrNull { distance <= stages[it] } ?: return null
            stepId = if (stage == stages.lastIndex) 0 else stages[stage].toInt()
        }
        val key = guide to stepId
        if (key in completed || (guide to 0) in completed) return null
        if (guide != null && stepId > (entered[guide] ?: Int.MAX_VALUE)) return null
        val old = pending[key]
        if (old != null) {
            if (old.accepted || old.reserved || (now - old.at <= 500 && abs(p.alongM - old.along) <= 5)) return null
            pending.remove(key)
        } else if (key in issued && key !in owed) return null
        val text = if (p.arrived) next(route, p, speedKmh) ?: return null
            else turnText(route, p, guide!!, stepId == 0)
        val notice = PlaybackNotice(text, guide, stage, ++attempts, stepId, stages)
        if (guide != null) {
            entered[guide] = minOf(entered[guide] ?: Int.MAX_VALUE, stepId)
            if (stepId == 0) finalTrigger.putIfAbsent(guide, stages.last())
        }
        issued += key
        owed.remove(key)
        pending[key] = Pending(notice, now, p.alongM)
        return notice
    }

    /** Atomically reserve this attempt before engine dispatch; a concurrent fix cannot replace it. */
    @Synchronized fun claimStart(notice: PlaybackNotice): Boolean {
        val key = notice.guide to notice.stepId
        val work = pending[key]?.takeIf { it.notice.attempt == notice.attempt } ?: return false
        if (key in completed) return false
        work.reserved = true
        return true
    }

    @Synchronized fun playback(notice: PlaybackNotice, outcome: SpeechOutcome) {
        val key = notice.guide to notice.stepId
        val work = pending[key]?.takeIf { it.notice.attempt == notice.attempt } ?: return
        when (outcome) {
            SpeechOutcome.ACCEPTED, SpeechOutcome.STARTED -> work.accepted = true
            SpeechOutcome.COMPLETED -> { pending.remove(key); completed += key }
            SpeechOutcome.DROPPED, SpeechOutcome.INTERRUPTED -> { pending.remove(key); if (notice.guide != null && notice.stage >= 0) owed += key }
        }
    }

    private val said = HashSet<Pair<Int, Int>>()
    private var arrivedSaid = false
    var lastEvent: Pair<Int?, Int>? = null
        private set

    @Synchronized fun reset() {
        said.clear()
        pending.clear()
        completed.clear()
        owed.clear()
        entered.clear()
        finalTrigger.clear()
        issued.clear()
        arrivedSaid = false
        lastEvent = null
    }

    /** Text to speak for this progress update, or null when nothing is due. */
    @Synchronized fun next(route: NaviRoute, p: RouteProgress, speedKmh: Double?): String? {
        if (!p.canAnnounceTurn) return null
        if (p.arrived) {
            if (p.estimated || p.positionUncertain) return null
            if (arrivedSaid) return null
            arrivedSaid = true
            lastEvent = null to -1
            return "목적지 ${route.goal.name} 부근에 도착했습니다."
        }
        val i = p.nextGuideIndex ?: return null
        val d = p.nextGuideDistanceM ?: return null
        val g = route.guides[i]
        // Arrival is confirmed separately by real fixes; do not speak a second destination prompt.
        if (g.maneuver == Maneuver.DESTINATION) return null
        val stages = stagesFor(speedKmh ?: 0.0)
        // The nearest stage the car has reached; earlier, skipped stages are not said late.
        val stage = stages.indices.lastOrNull { d <= stages[it] } ?: return null
        if (!said.add(i to stage)) return null
        stages.indices.filter { it < stage }.forEach { said.add(i to it) }
        lastEvent = i to stage
        return turnText(route, p, i, stage == stages.lastIndex)
    }

    private fun turnText(route: NaviRoute, p: RouteProgress, i: Int, last: Boolean): String {
        val text = speakable(route.guides[i])
        val then = if (last) followUp(route, i) else ""
        val notice = if (last) "$text.$then" else "${distanceWords(p.nextGuideDistanceM!!)} 앞, $text."
        return if (p.estimated) "추정 위치 기준, $notice" else notice
    }

    /** "Then soon after, …" when the following turn comes within 250 m. */
    private fun followUp(route: NaviRoute, i: Int): String {
        val n = route.guides.getOrNull(i + 1) ?: return ""
        if (n.distanceM > 250 || n.maneuver == Maneuver.STRAIGHT || n.maneuver == Maneuver.DESTINATION) return ""
        return " 이어서 ${speakable(n)}."
    }

    companion object {
        fun stagesFor(speedKmh: Double): DoubleArray = when {
            speedKmh >= 80 -> doubleArrayOf(2000.0, 1000.0, 500.0, 150.0)
            speedKmh >= 40 -> doubleArrayOf(700.0, 300.0, 80.0)
            else -> doubleArrayOf(300.0, 100.0, 30.0)
        }

        /** NAVER instructions read as they are, e.g. "'서울역' 방면으로 우회전". */
        fun speakable(g: RouteGuide): String {
            val t = g.instructions.replace("'", "").replace("\"", "").trim()
            return t.ifBlank { g.maneuver.label }
        }

        fun distanceWords(m: Double): String = when {
            m >= 950 -> {
                val km = (m / 100).roundToInt() / 10.0
                if (km % 1.0 == 0.0) "${km.toInt()}킬로미터" else "${km}킬로미터"
            }
            m >= 100 -> "${(m / 100).roundToInt() * 100}미터"
            else -> "${((m / 10).roundToInt() * 10).coerceAtLeast(10)}미터"
        }
    }
}
