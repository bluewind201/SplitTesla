package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Where a point falls on a route: metres from the start and metres off the
 * line; [rightM] is the same offset signed, positive to the right of the
 * direction of travel and negative to the left.
 */
data class Projection(val alongM: Double, val lateralM: Double, val segment: Int, val rightM: Double = 0.0)

/**
 * Distances along a route's path. Each segment is measured in a flat
 * projection around its own start, which is accurate to a few metres for the
 * short segments a road route is made of.
 */
class RouteGeometry(val path: List<LatLon>) {
    /** Metres from the start to each path point. */
    val cumulative: DoubleArray = DoubleArray(path.size).also { cum ->
        for (i in 1 until path.size) cum[i] = cum[i - 1] + metres(path[i - 1], path[i])
    }
    val lengthM: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    fun alongOf(pointIndex: Int): Double = cumulative[pointIndex.coerceIn(0, cumulative.lastIndex)]

    /** Nearest point on segments [from]..[to] (segment i joins point i and i+1). */
    fun project(p: LatLon, from: Int = 0, to: Int = path.size - 2): Projection? {
        if (path.size < 2) return path.firstOrNull()?.let { Projection(0.0, metres(it, p), 0) }
        var best: Projection? = null
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(path.size - 2)) {
            val a = path[i]
            val b = path[i + 1]
            val kx = cos(a.lat * PI / 180) * 111_320.0
            val ky = 110_574.0
            val bx = (b.lon - a.lon) * kx
            val by = (b.lat - a.lat) * ky
            val px = (p.lon - a.lon) * kx
            val py = (p.lat - a.lat) * ky
            val len2 = bx * bx + by * by
            val t = if (len2 < 1e-6) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            val lateral = sqrt(dx * dx + dy * dy)
            if (best == null || lateral < best.lateralM) {
                // East-north axes: a negative cross product puts the point right of the segment.
                val cross = bx * dy - by * dx
                val side = if (cross > 0) -lateral else lateral
                best = Projection(cumulative[i] + t * sqrt(len2), lateral, i, side)
            }
        }
        return best
    }

    /** The road's direction at [alongM], in degrees clockwise from north. */
    fun bearingAt(alongM: Double): Double {
        if (path.size < 2) return 0.0
        val d = alongM.coerceIn(0.0, lengthM)
        var i = cumulative.indexOfFirst { it > d }.let { if (it <= 0) 1 else it }.coerceAtMost(path.size - 1)
        // Duplicate points make a zero-length segment with no direction; look further on.
        while (i < path.size - 1 && metres(path[i - 1], path[i]) < 0.5) i++
        val a = path[i - 1]
        val b = path[i]
        val dx = (b.lon - a.lon) * cos(a.lat * PI / 180)
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    /** The path point at [alongM] metres from the start. */
    fun pointAt(alongM: Double): LatLon {
        if (path.isEmpty()) return LatLon(0.0, 0.0)
        val d = alongM.coerceIn(0.0, lengthM)
        var i = cumulative.indexOfFirst { it >= d }.let { if (it <= 0) 1 else it }
        if (i >= path.size) i = path.size - 1
        val seg = cumulative[i] - cumulative[i - 1]
        val t = if (seg <= 0) 0.0 else (d - cumulative[i - 1]) / seg
        val a = path[i - 1]
        val b = path[i]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    companion object {
        fun metres(a: LatLon, b: LatLon): Double {
            val kx = cos((a.lat + b.lat) / 2 * PI / 180) * 111_320.0
            val dx = (b.lon - a.lon) * kx
            val dy = (b.lat - a.lat) * 110_574.0
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/** Progress along the active route, recomputed on every position. */
data class RouteProgress(
    val alongM: Double,
    val remainingM: Double,
    val remainingMs: Long,
    /** Metres between the car and the route line. */
    val lateralM: Double,
    /** Several fixes in a row too far from the line; time to search again. */
    val offRoute: Boolean,
    val nextGuideIndex: Int?,
    val nextGuideDistanceM: Double?,
    val sectionIndex: Int?,
    val arrived: Boolean,
)

/**
 * Follows the car along a route. GPS fixes snap to the nearest part of the
 * line ahead of the last known spot, so a road running alongside or back over
 * the route does not make the progress jump. In a tunnel, [advance] moves the
 * progress forward by the distance driven (from the car's speed).
 *
 * Tunnels and their mouths are where GPS lies: fixes drift beside the road or
 * run straight on through a bend. So a fix vaguer than [trustedAccuracyM]
 * never counts towards leaving the route, and the first [GRACE_FIXES] fixes
 * after a stretch of dead reckoning may pull the progress back but cannot
 * trigger a new search.
 */
class RouteTracker(
    val route: NaviRoute,
    private val offRouteM: Double = 45.0,
    private val offRouteFixes: Int = 3,
    private val trustedAccuracyM: Double = 30.0,
) {
    val geometry = RouteGeometry(route.path)
    private val guideAlong = route.guides.map { geometry.alongOf(it.pointIndex) }
    private val legStart = guideAlong.mapIndexed { i, _ -> if (i == 0) 0.0 else guideAlong[i - 1] }
    private var segment = 0
    private var along = 0.0
    private var misses = 0
    private var lastLateral = 0.0
    private var grace = 0

    val alongM: Double get() = along

    fun update(p: LatLon, accuracyM: Double? = null): RouteProgress {
        // Look a little behind (GPS jitter) and well ahead (about 3 km of points).
        val from = (segment - 3).coerceAtLeast(0)
        val to = (segment + 400).coerceAtMost(route.path.size - 2)
        val hit = geometry.project(p, from, to)
        if (hit != null) {
            lastLateral = hit.lateralM
            val limit = offRouteM + (accuracyM ?: 0.0).coerceAtMost(40.0)
            if (hit.lateralM <= limit) {
                misses = 0
                // Never slide backwards more than a little; a jittery fix is not a U-turn.
                // Coming out of a tunnel the estimate may have run ahead, so then allow more.
                if (hit.alongM >= along - (if (grace > 0) MAX_TUNNEL_OVERSHOOT_M else 30.0)) {
                    along = hit.alongM
                    segment = hit.segment
                }
            } else if (grace == 0 && (accuracyM == null || accuracyM <= trustedAccuracyM)) {
                misses++
            }
        }
        if (grace > 0) grace--
        return progress()
    }

    /** Where to draw the car: on the line at the current progress. */
    fun snapped(): LatLon = geometry.pointAt(along)

    /** The road's direction at the car, degrees clockwise from north. */
    fun heading(): Double = geometry.bearingAt(along)

    /** Dead reckoning while GPS is out: move on by [metres] along the line. */
    fun advance(metres: Double): RouteProgress {
        if (metres > 0) {
            along = (along + metres).coerceAtMost(geometry.lengthM)
            while (segment < route.path.size - 2 && geometry.cumulative[segment + 1] <= along) segment++
        }
        misses = 0
        grace = GRACE_FIXES
        return progress()
    }

    fun progress(): RouteProgress {
        val remaining = (geometry.lengthM - along).coerceAtLeast(0.0)
        val next = guideAlong.indexOfFirst { it > along + 5 }.takeIf { it >= 0 }
        return RouteProgress(
            alongM = along,
            remainingM = remaining,
            remainingMs = remainingMs(),
            lateralM = lastLateral,
            offRoute = misses >= offRouteFixes,
            nextGuideIndex = next,
            nextGuideDistanceM = next?.let { guideAlong[it] - along },
            sectionIndex = sectionAt(along),
            arrived = remaining < 30,
        )
    }

    /** Time left: the rest of the current guide leg pro rata, then every later leg in full. */
    private fun remainingMs(): Long {
        if (route.guides.isEmpty()) {
            val f = if (geometry.lengthM > 0) (geometry.lengthM - along) / geometry.lengthM else 0.0
            return (route.durationMs * f).toLong()
        }
        var ms = 0.0
        route.guides.forEachIndexed { i, g ->
            val start = legStart[i]
            val end = guideAlong[i]
            when {
                end <= along -> Unit
                start >= along -> ms += g.durationMs
                else -> ms += g.durationMs * ((end - along) / (end - start).coerceAtLeast(1.0))
            }
        }
        return ms.toLong()
    }

    private fun sectionAt(alongM: Double): Int? = route.sections.indexOfFirst { s ->
        alongM >= geometry.alongOf(s.pointIndex) && alongM <= geometry.alongOf(s.pointIndex + s.pointCount)
    }.takeIf { it >= 0 }

    fun guideAlong(index: Int): Double = guideAlong[index]

    companion object {
        /** Fixes after a GPS gap that are not trusted to say we left the route. */
        const val GRACE_FIXES = 8

        /** How far behind the tunnel estimate the first good fix may put us. */
        private const val MAX_TUNNEL_OVERSHOOT_M = 400.0
    }
}
