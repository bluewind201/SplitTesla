package com.splittesla.core.navi

import kotlin.math.roundToInt

/**
 * Decides when to speak the next turn. Faster driving gets earlier and more
 * notices (highway: 2 km, 1 km, 500 m and at the turn; town: 700 m, 300 m and
 * at the turn). Each notice is said once per guide.
 */
class GuidanceAnnouncer {
    private val said = HashSet<Pair<Int, Int>>()
    private var arrivedSaid = false

    fun reset() {
        said.clear()
        arrivedSaid = false
    }

    /** Text to speak for this progress update, or null when nothing is due. */
    fun next(route: NaviRoute, p: RouteProgress, speedKmh: Double?): String? {
        if (p.arrived) {
            if (arrivedSaid) return null
            arrivedSaid = true
            return "목적지 ${route.goal.name} 부근에 도착했습니다. 안내를 마칩니다."
        }
        val i = p.nextGuideIndex ?: return null
        val d = p.nextGuideDistanceM ?: return null
        val g = route.guides[i]
        val stages = stagesFor(speedKmh ?: 0.0)
        // The nearest stage the car has reached; earlier, skipped stages are not said late.
        val stage = stages.indices.lastOrNull { d <= stages[it] } ?: return null
        if (!said.add(i to stage)) return null
        stages.indices.filter { it < stage }.forEach { said.add(i to it) }
        val text = speakable(g)
        val last = stage == stages.lastIndex
        val then = if (last) followUp(route, i) else ""
        return if (last) "$text.$then" else "${distanceWords(d)} 앞, $text."
    }

    /** "Then soon after, …" when the following turn comes within 250 m. */
    private fun followUp(route: NaviRoute, i: Int): String {
        val n = route.guides.getOrNull(i + 1) ?: return ""
        if (n.distanceM > 250 || n.maneuver == Maneuver.STRAIGHT) return ""
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
