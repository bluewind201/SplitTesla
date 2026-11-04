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
    fun project(p: LatLon, from: Int = 0, to: Int = path.size - 2, minAlongM: Double = 0.0, maxAlongM: Double = lengthM, courseDeg: Double? = null): Projection? {
        if (path.size < 2) return path.firstOrNull()?.let { Projection(0.0, metres(it, p), 0) }
        var best: Projection? = null
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(path.size - 2)) {
            if (cumulative[i + 1] < minAlongM || cumulative[i] > maxAlongM) continue
            val a = path[i]
            val b = path[i + 1]
            val kx = cos(a.lat * PI / 180) * 111_320.0
            val ky = 110_574.0
            val bx = (b.lon - a.lon) * kx
            val by = (b.lat - a.lat) * ky
            val px = (p.lon - a.lon) * kx
            val py = (p.lat - a.lat) * ky
            val len2 = bx * bx + by * by
            if (courseDeg != null && len2 >= 1.0) {
                val bearing = (Math.toDegrees(atan2(bx, by)) + 360) % 360
                val difference = kotlin.math.abs(((bearing - courseDeg + 540) % 360) - 180)
                if (difference > 75.0) continue
            }
            val length = sqrt(len2)
            val low = if (length < 1e-6) 0.0 else ((minAlongM - cumulative[i]) / length).coerceIn(0.0, 1.0)
            val high = if (length < 1e-6) 1.0 else ((maxAlongM - cumulative[i]) / length).coerceIn(low, 1.0)
            val t = if (len2 < 1e-6) 0.0 else ((px * bx + py * by) / len2).coerceIn(low, high)
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
    /** Progress from speed integration rather than a real location fix. */
    val estimated: Boolean = false,
    /** Real GPS is missing or cannot yet be matched to a consistent route position. */
    val positionUncertain: Boolean = false,
    /** Explicit permission for a bounded prediction, independent of real-position confidence. */
    val estimatedTurnAllowed: Boolean = false,
) {
    val canAnnounceTurn: Boolean get() = if (estimated) estimatedTurnAllowed else !positionUncertain
}

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
    private val trustedAccuracyM: Double = TRUSTED_ACCURACY_M,
) {
    val geometry = RouteGeometry(route.path)
    private val guideAlong = route.guides.map { geometry.alongOf(it.pointIndex) }
    private val legStart = guideAlong.mapIndexed { i, _ -> if (i == 0) 0.0 else guideAlong[i - 1] }
    private var segment = 0
    private var along = 0.0
    private var misses = 0
    private var lastLateral = 0.0
    private var grace = 0
    private val fixGate = FixGate(maxAccuracyM = trustedAccuracyM)
    private var confirmedArrivalFixes = 0
    private var arrivalFixAt: Long? = null
    private var estimated = false
    private var realAlong: Double? = null
    private var realPoint: LatLon? = null
    private var realAt: Long? = null
    private var positionUncertain = true
    private var furthestRealAlong: Double? = null
    private var routeAnchorUsable = false
    private var estimatedTurnAllowed = false
    private var estimatedElapsedMs = 0L

    val alongM: Double get() = along

    /** Poor observations age the old anchor; they never renew it or enter the trusted motion track. */
    private fun observeFixTime(timeMs: Long?) {
        val anchorAt = realAt ?: return
        if (timeMs != null && timeMs >= anchorAt) {
            estimatedElapsedMs = maxOf(estimatedElapsedMs, (timeMs - anchorAt).coerceAtMost(30_001))
        }
    }

    /** Shared admission for Android: a null result must not update last-good position or time. */
    fun validateFix(p: LatLon, accuracyM: Double?, timeMs: Long): FixGate.Accepted? {
        observeFixTime(timeMs)
        return fixGate.accept(p, timeMs, accuracyM)
    }

    fun update(p: LatLon, accuracyM: Double? = null, timeMs: Long? = null, acceptedFix: FixGate.Accepted? = null): RouteProgress {
        observeFixTime(timeMs)
        if (!fixGate.acceptsAccuracy(accuracyM)) {
            invalidatePositionEvidence()
            return progress()
        }
        // Android supplies this tracker's admission result; do not run the motion gate twice.
        val evidence = acceptedFix ?: timeMs?.let { validateFix(p, accuracyM, it) }
        if (timeMs != null && evidence == null) {
            invalidatePositionEvidence()
            return progress()
        }
        // Look a little behind (GPS jitter) and well ahead (about 3 km of points).
        val reacquiring = evidence?.reacquired == true || grace > 0
        val from = if (reacquiring) 0 else (segment - 3).coerceAtLeast(0)
        val to = if (reacquiring) route.path.size - 2 else (segment + 400).coerceAtMost(route.path.size - 2)
        val limit = offRouteM + (accuracyM ?: 0.0).coerceAtMost(40.0)
        val local = if (realPoint != null) geometry.project(p, 0, route.path.size - 2,
            (along - MAX_TUNNEL_OVERSHOOT_M).coerceAtLeast(0.0), (along + 150.0).coerceAtMost(geometry.lengthM), courseDeg = evidence?.courseDeg) else null
        val global = geometry.project(p, from, to, courseDeg = evidence?.courseDeg)
        val travelled = realPoint?.let { RouteGeometry.metres(it, p) }
        val supportedForward = travelled?.let { it * 1.5 + 40.0 } ?: Double.POSITIVE_INFINITY
        // Prefer the local leg when the nearest point requires unsupported route travel.
        // Plausible progress on the same leg must not be clamped to the local search window.
        val elapsed = timeMs?.let { now -> realAt?.let { now - it } }
        // After a coherent GPS reacquisition, a turn can make route travel longer than displacement.
        // Only allow this when the local leg has no compatible course and the global leg does.
        val turningReacquisition = evidence?.reacquired == true && evidence.courseDeg != null &&
            global != null && global.lateralM <= (accuracyM ?: 15.0).coerceIn(10.0, 30.0) &&
            (local == null || local.lateralM > limit) && realAlong != null && elapsed != null &&
            global.alongM - realAlong!! in 0.0..(75.0 * elapsed.coerceIn(0, 30_000) / 1000.0 + 40.0)
        val globalSupported = global == null || realAlong == null ||
            global.alongM - realAlong!! <= supportedForward || turningReacquisition
        val hit = if (globalSupported) global else local?.takeIf { it.lateralM <= limit } ?: global
        if (hit != null) {
            val prevAlong = realAlong
            val nearEstimate = estimated && kotlin.math.abs(hit.alongM - along) <= 100 &&
                realPoint?.let { RouteGeometry.metres(it, p) >= kotlin.math.abs(hit.alongM - (prevAlong ?: along)) * 0.5 } == true
            if (timeMs != null && prevAlong != null && hit.alongM - prevAlong > supportedForward && !nearEstimate && !turningReacquisition) {
                rejectProjection(accuracyM)
                return progress()
            }
            lastLateral = hit.lateralM
            if (hit.lateralM <= limit) {
                // Never slide backwards more than a little; a jittery fix is not a U-turn.
                // Coming out of a tunnel the estimate may have run ahead, so then allow more.
                val backFloor = maxOf((furthestRealAlong ?: along) - 30.0,
                    along - (if (estimated && grace > 0) MAX_TUNNEL_OVERSHOOT_M else 30.0))
                if (evidence?.anchorReset == true || hit.alongM >= backFloor) {
                    misses = 0
                    along = hit.alongM
                    segment = hit.segment
                    estimated = false
                    positionUncertain = false
                    routeAnchorUsable = true
                    estimatedTurnAllowed = false
                    estimatedElapsedMs = 0
                    realAt = timeMs
                    realAlong = hit.alongM
                    realPoint = p
                    furthestRealAlong = if (evidence?.anchorReset == true) hit.alongM
                        else maxOf(furthestRealAlong ?: hit.alongM, hit.alongM)
                } else {
                    rejectProjection(accuracyM)
                    return progress()
                }
            } else if (grace == 0 && (accuracyM == null || accuracyM <= trustedAccuracyM)) {
                misses++
                invalidateRouteAnchor()
            } else {
                invalidateRouteAnchor()
            }
        } else {
            rejectProjection(accuracyM)
            return progress()
        }
        val nearDestination = !positionUncertain && geometry.lengthM - along < 30 && lastLateral <= 30 &&
            geometry.path.lastOrNull()?.let { RouteGeometry.metres(p, it) <= 40 } == true &&
            (accuracyM == null || accuracyM <= trustedAccuracyM) && evidence?.reacquired != true
        if (!nearDestination) invalidateArrival()
        else if (timeMs == null || arrivalFixAt == null || timeMs - arrivalFixAt!! >= 500) {
            confirmedArrivalFixes++
            arrivalFixAt = timeMs
        }
        if (grace > 0) grace--
        return progress()
    }

    private fun rejectProjection(accuracyM: Double?) {
        invalidateRouteAnchor()
        if (accuracyM == null || accuracyM <= trustedAccuracyM) misses++
    }

    private fun invalidateRouteAnchor() {
        routeAnchorUsable = false
        invalidatePositionEvidence()
    }

    /** A bad GPS sample is not evidence that the last matched route is wrong. */
    fun invalidatePositionEvidence() {
        invalidateEstimatedTurn()
        positionUncertain = true
        invalidateArrival()
    }

    /** The caller owns vehicle speed freshness and the real last-fix deadline. */
    fun invalidateEstimatedTurn() {
        estimatedTurnAllowed = false
    }

    fun invalidateArrival() {
        confirmedArrivalFixes = 0
        arrivalFixAt = null
    }

    /** Where to draw the car: on the line at the current progress. */
    fun snapped(): LatLon = geometry.pointAt(along)

    /** The road's direction at the car, degrees clockwise from north. */
    fun heading(): Double = geometry.bearingAt(along)

    /**
     * Geometry integration only. The caller must supply fresh measured speed and enforce the
     * last-real-fix deadline (NaviHub uses SpeedEvidence). Legacy one-argument calls assume
     * a one-second authorized step; turn permission adds step and 30-second integration limits.
     * This never makes the real position certain or confirms arrival/waypoint/camera passage.
     */
    fun advance(metres: Double, elapsedMs: Long = 1000, turnPredictionValid: Boolean = true): RouteProgress {
        estimated = true
        invalidateArrival()
        estimatedElapsedMs = if (elapsedMs > 0) (estimatedElapsedMs + elapsedMs.coerceAtMost(30_001)).coerceAtMost(30_001)
            else 30_001
        estimatedTurnAllowed = routeAnchorUsable && turnPredictionValid && elapsedMs in 1..2000 &&
            estimatedElapsedMs <= 30_000 && metres.isFinite() && metres > 0 &&
            metres <= 250.0 / 3.6 * elapsedMs / 1000.0 + 0.001
        if (metres.isFinite() && metres > 0) {
            along = (along + metres).coerceAtMost(geometry.lengthM)
            while (segment < route.path.size - 2 && geometry.cumulative[segment + 1] <= along) segment++
        }
        if (routeAnchorUsable) {
            misses = 0
            grace = GRACE_FIXES
        }
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
            arrived = !estimated && !positionUncertain && confirmedArrivalFixes >= 2,
            estimated = estimated,
            positionUncertain = positionUncertain,
            estimatedTurnAllowed = estimatedTurnAllowed,
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
        const val TRUSTED_ACCURACY_M = 30.0

        /** Fixes after a GPS gap that are not trusted to say we left the route. */
        const val GRACE_FIXES = 8

        /** How far behind the tunnel estimate the first good fix may put us. */
        private const val MAX_TUNNEL_OVERSHOOT_M = 400.0
    }
}
