package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon
import kotlin.math.max

/** Position evidence only: derived speed must never be used as measured speeding evidence.
 * Times are monotonic milliseconds from the location sample, not its delivery time.
 */
class FixGate(private val maxAccuracyM: Double = 60.0) {
    data class Accepted(
        val derivedKmh: Double?, val reacquired: Boolean, val continuous: Boolean,
        /** Course from a sufficiently long, recent real GPS track; never measured vehicle speed. */
        val courseDeg: Double? = null,
        /** A better-quality track replaced the single weak startup anchor. */
        val anchorReset: Boolean = false,
    )
    private data class Fix(val point: LatLon, val at: Long, val accuracy: Double)
    private var last: Fix? = null
    private var seenAt: Long? = null
    private var candidate: Fix? = null
    private var candidateSince = 0L
    private var candidateCount = 0
    private var candidateDelta: Pair<Double, Double>? = null
    private var pendingReachM: Double? = null
    private var previousTrusted: Fix? = null
    private var acceptedCount = 0
    private val courseTrack = ArrayDeque<Fix>()
    private val candidateTrack = ArrayDeque<Fix>()

    private fun course(fix: Fix, track: Collection<Fix>): Double? {
        val from = track.lastOrNull {
            fix.at - it.at in 500..8000 && RouteGeometry.metres(it.point, fix.point) >=
                max(10.0, 2 * max(it.accuracy, fix.accuracy))
        } ?: return null
        return RouteGeometry(listOf(from.point, fix.point)).bearingAt(0.0)
    }

    private fun rememberCourse(fix: Fix) {
        courseTrack.addLast(fix)
        while (courseTrack.size > 32) courseTrack.removeFirst()
    }

    fun acceptsAccuracy(accuracyM: Double?): Boolean =
        accuracyM == null || (accuracyM.isFinite() && accuracyM in 0.0..maxAccuracyM)

    fun accept(point: LatLon, at: Long, accuracyM: Double? = null): Accepted? {
        if (!point.lat.isFinite() || !point.lon.isFinite() || point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0 ||
            at < 0 || !acceptsAccuracy(accuracyM)) return null
        if (seenAt?.let { at <= it } == true) return null
        seenAt = at
        val fix = Fix(point, at, accuracyM ?: 20.0)
        val prev = last
        if (prev == null) {
            last = fix
            acceptedCount++
            rememberCourse(fix)
            return Accepted(null, false, false)
        }
        val elapsed = at - prev.at
        val distance = RouteGeometry.metres(prev.point, point)
        // A long outage is not permission to accept any distant coordinate.
        val reachM = 75.0 * elapsed.coerceAtMost(5000) / 1000.0 + max(prev.accuracy, fix.accuracy).coerceAtMost(20.0)
        // Once a point is suspect, elapsed time alone must not turn the same jump into a valid fix.
        val reachable = distance <= (pendingReachM ?: reachM)
        val history = previousTrusted
        val seconds = elapsed / 1000.0
        val historySeconds = history?.let { (prev.at - it.at) / 1000.0 }
        val predicted = if (history != null && historySeconds != null && historySeconds in 0.5..30.0) {
            val ratio = seconds / historySeconds
            LatLon(prev.point.lat + (prev.point.lat - history.point.lat) * ratio,
                prev.point.lon + (prev.point.lon - history.point.lon) * ratio)
        } else null
        val regularSparseCadence = historySeconds != null && historySeconds >= 8.0 &&
            seconds in (historySeconds * 0.5)..(historySeconds * 1.5)
        val followsTrustedMotion = predicted != null && elapsed <= 30_000 &&
            (elapsed <= 8000 || regularSparseCadence) &&
            distance <= 75.0 * seconds + max(prev.accuracy, fix.accuracy).coerceAtMost(20.0) &&
            RouteGeometry.metres(predicted, point) <= (20.0 + seconds * 3).coerceAtMost(45.0)
        val lowRateReachable = pendingReachM == null && elapsed in 5001..8000 &&
            distance <= 75.0 * seconds + max(prev.accuracy, fix.accuracy).coerceAtMost(20.0)
        if ((reachable && elapsed <= 5000) || followsTrustedMotion || lowRateReachable) {
            val continuous = (reachable && elapsed <= 5000) ||
                (pendingReachM == null && (elapsed <= 8000 || followsTrustedMotion))
            val direction = course(fix, courseTrack)
            previousTrusted = prev
            last = fix
            acceptedCount++
            candidate = null
            candidateDelta = null
            pendingReachM = null
            candidateCount = 0
            candidateTrack.clear()
            rememberCourse(fix)
            return Accepted((distance / seconds * 3.6).takeIf { elapsed >= 500 && continuous }, !continuous, continuous, direction)
        }
        if (pendingReachM == null) pendingReachM = reachM
        val c = candidate
        val delta = c?.let { (point.lat - it.point.lat) * 110574.0 to
            (point.lon - it.point.lon) * 111320.0 * kotlin.math.cos(Math.toRadians(point.lat)) }
        val previousDelta = candidateDelta
        val consistentDirection = if (delta != null && previousDelta != null) {
            val a = kotlin.math.hypot(delta.first, delta.second)
            val b = kotlin.math.hypot(previousDelta.first, previousDelta.second)
            a < 10 || b < 10 || delta.first * previousDelta.first + delta.second * previousDelta.second >= 0.5 * a * b
        } else true
        val coherent = c != null && at - c.at in 500..30_000 && consistentDirection &&
            RouteGeometry.metres(c.point, point) <= 75.0 * (at - c.at) / 1000.0 + 15.0
        if (!coherent) {
            candidateSince = at
            candidateCount = 1
            candidateTrack.clear()
        } else candidateCount++
        candidateTrack.addLast(fix)
        while (candidateTrack.size > 64) candidateTrack.removeFirst()
        candidateDelta = if (coherent) delta else null
        candidate = fix
        // Reacquire only an independently time-consistent track, never a rejection count alone.
        // Stationary candidates are permitted after a real outage. Consumers must reset evidence.
        val betterBootstrap = acceptedCount == 1 && prev.accuracy >= 20 && fix.accuracy <= 10 &&
            candidateCount >= 3 && at - candidateSince >= 2000 && consistentDirection
        if (betterBootstrap || (elapsed >= 10_000 && at - candidateSince >= 5000 && candidateCount >= 4)) {
            val direction = course(fix, candidateTrack)
            previousTrusted = if (betterBootstrap) null else c
            courseTrack.clear()
            candidateTrack.forEach { rememberCourse(it) }
            candidateTrack.clear()
            last = fix
            acceptedCount++
            candidate = null
            candidateDelta = null
            pendingReachM = null
            candidateCount = 0
            return Accepted(null, true, false, direction, anchorReset = betterBootstrap)
        }
        return null
    }
}

/** Only finite measured speeds and non-future, fresh source timestamps count. */
object SpeedEvidence {
    /** Map a fresh monotonic sample age to wall time; receiving it later must not renew its age. */
    fun fixSampleAtMs(receivedAtMs: Long, sampleAgeNanos: Long): Long? {
        if (sampleAgeNanos !in 0..3_000_000_000L || receivedAtMs <= 0) return null
        return (receivedAtMs - sampleAgeNanos / 1_000_000).takeIf { it > 0 }
    }

    fun measured(kmh: Double?): Double? = kmh?.takeIf { it.isFinite() && it in 0.0..250.0 }
    fun fresh(kmh: Double?, at: Long, now: Long, maxAgeMs: Long = 3000): Double? =
        measured(kmh)?.takeIf { at > 0 && now - at in 0..maxAgeMs }

    /** Estimated progress has a short lifetime even if BLE keeps reporting speed. */
    fun deadReckoningKmh(now: Long, lastFixAt: Long, carKmh: Double?, carAt: Long): Double? =
        fresh(carKmh, carAt, now)?.takeIf { lastFixAt > 0 && now - lastFixAt in 3001..30_000 }
}
