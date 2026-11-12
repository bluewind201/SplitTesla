package com.splittesla.core.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the driver should be told. */
sealed class CameraAlert {
    abstract val camera: SpeedCamera
    abstract val distanceM: Double

    /** Early warning; how early depends on speed. */
    data class Approaching(override val camera: SpeedCamera, override val distanceM: Double, val overLimit: Boolean) : CameraAlert()

    /** The 100 m warning. */
    data class Final(override val camera: SpeedCamera, override val distanceM: Double, val overLimit: Boolean) : CameraAlert()

    /** Entered a section (average speed) enforcement zone. */
    data class SectionStart(override val camera: SpeedCamera, override val distanceM: Double) : CameraAlert()

    /** Passed the section's end camera. [averageKmh] is null when the start was not seen. */
    data class SectionEnd(override val camera: SpeedCamera, override val distanceM: Double, val averageKmh: Double?) : CameraAlert()
}

/** Live view for the gauge and the floating dashboard. */
data class CameraStatus(
    val ahead: SpeedCamera? = null,
    val distanceM: Double? = null,
    /** Average speed so far in a section zone. */
    val sectionAverageKmh: Double? = null,
    val sectionLimitKmh: Int? = null,
    /** Metres left to the section's end camera, when the section length is known. */
    val sectionRemainingM: Double? = null,
    /** Highest steady speed for the rest of the section that keeps the average at the limit. */
    val sectionSafeKmh: Double? = null,
    /** Where the position came from: "GPS", "차량", "S3XY". */
    val positionSource: String? = null,
) {
    val active: Boolean get() = ahead != null || sectionLimitKmh != null

    /** The limit that matters now: the camera ahead, else the section being driven. */
    val limitKmh: Int? get() = ahead?.limitKmh ?: sectionLimitKmh
}

/**
 * Decides when to warn about speed cameras from a stream of positions (the
 * phone's GPS, the car's own GPS over BLE, or the Commander's). A camera
 * counts when it lies ahead within [sideM] (plus a little per metre ahead) of
 * the path we are about to drive, bent by how fast we are turning, and the
 * distance to it is shrinking.
 *
 * A camera whose limit is [ROAD_MISMATCH_KMH] or more away from the car's
 * own map limit for the road is taken to be on another road (protected zone
 * cameras are always kept). Each camera warns once early and once at
 * [finalM].
 */
class CameraAlerter(
    private val index: CameraIndex,
    private val finalM: Double = 100.0,
    private val sideM: Double = 30.0,
) {
    private val warnedEarly = HashSet<String>()
    private val warnedFinal = HashSet<String>()
    private val passed = HashSet<String>()
    private val track = ArrayDeque<Fix>()
    private val recentDistance = HashMap<String, ArrayDeque<Double>>()
    private var lastAhead: Pair<SpeedCamera, Double>? = null
    private var lastFix: Fix? = null

    private var section: SpeedCamera? = null
    private var sectionStartMs = 0L
    private var sectionMeters = 0.0

    var status = CameraStatus()
        private set

    val cameraCount: Int get() = index.size

    /** Early warning distance: about 12 s of travel, 200–600 m. */
    fun earlyDistanceM(speedKmh: Double): Double = (speedKmh / 3.6 * 12).coerceIn(200.0, 600.0)

    /** No position for a while: forget what was ahead so the screen does not show it frozen. */
    fun clear() {
        lastAhead = null
        track.clear()
        recentDistance.clear()
        lastFix = null
        status = status.copy(ahead = null, distanceM = null)
    }

    /**
     * Feeds one position. [headingDeg] is the course over ground when known;
     * it is trusted only once moving. [roadLimitKmh] is the limit the car's
     * own map gives for this road (from the Commander).
     */
    fun update(
        lat: Double,
        lon: Double,
        headingDeg: Double?,
        speedKmh: Double,
        timeMs: Long,
        roadLimitKmh: Int? = null,
        positionSource: String? = null,
    ): List<CameraAlert> {
        val alerts = ArrayList<CameraAlert>()
        val prev = lastFix
        val continuous = prev != null && timeMs - prev.timeMs in 1..MAX_GAP_MS &&
            Geo.distanceM(prev.lat, prev.lon, lat, lon) < MAX_JUMP_M
        if (!continuous) {
            track.clear()
            recentDistance.clear()
            lastAhead = null
        }
        if (prev != null && continuous && section != null) sectionMeters += Geo.distanceM(prev.lat, prev.lon, lat, lon)

        val derived = if (prev != null && continuous) Geo.distanceM(prev.lat, prev.lon, lat, lon) / ((timeMs - prev.timeMs) / 1000.0) * 3.6 else null
        val movement = speedKmh.takeIf { it > 0 } ?: derived ?: 0.0
        val course = headingDeg?.takeIf { it.isFinite() }
        val heading = (if (movement >= 20) course else null) ?: trackHeading(lat, lon) ?: course
        val curvature = heading?.let { curvature(lat, lon, it) } ?: 0.0
        val fix = Fix(lat, lon, heading, timeMs)
        track.addLast(fix)
        while (track.size > TRACK_FIXES) track.removeFirst()
        lastFix = fix

        val early = earlyDistanceM(movement)
        val moving = heading != null && movement >= MIN_MOVING_KMH
        val candidates = if (!moving) emptyList() else index.near(lat, lon, early + 50)
            .map { it to Geo.distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { (c, d) -> c.id !in passed && (onPath(lat, lon, heading!!, curvature, c, d) || (d < 40 && c.id == lastAhead?.first?.id)) }
            .filter { (c, _) -> !onOtherRoad(c, roadLimitKmh) }
        rememberDistances(candidates)
        val ahead = candidates.filter { (c, d) -> closing(c.id) || (d < 40 && c.id == lastAhead?.first?.id) }.minByOrNull { it.second }

        // The camera we were closing on is now behind us, or off the path after we got near it: passed.
        val was = lastAhead
        if (was != null && moving && ahead?.first?.id != was.first.id) {
            val cam = was.first
            val d = Geo.distanceM(lat, lon, cam.latitude, cam.longitude)
            val behind = Geo.angleDiff(heading!!, Geo.bearing(lat, lon, cam.latitude, cam.longitude)) >= 90
            if ((behind && d < PASS_NEAR_M) || was.second < 50) {
                if (passed.add(cam.id)) onPassed(cam, timeMs, alerts)
            }
        }

        if (ahead != null) {
            val (cam, d) = ahead
            val over = speedKmh > cam.limitKmh
            if (d <= finalM && warnedFinal.add(cam.id)) {
                warnedEarly.add(cam.id)
                alerts += CameraAlert.Final(cam, d, over)
            } else if (d > finalM && d <= early && cam.id !in warnedFinal && warnedEarly.add(cam.id)) {
                alerts += CameraAlert.Approaching(cam, d, over)
            }
        }
        lastAhead = ahead

        // Forget cameras that are well behind so a return trip warns again.
        if (warnedEarly.size + passed.size > 40) {
            val nearby = index.near(lat, lon, 1500.0).map { it.id }.toSet()
            warnedEarly.retainAll(nearby)
            warnedFinal.retainAll(nearby)
            passed.retainAll(nearby)
        }

        val s = section
        if (s != null && (sectionMeters > (s.sectionLengthM ?: 20_000) + 2_000 || timeMs - sectionStartMs > 1_800_000)) section = null // missed the end camera
        val inSection = section
        val elapsedS = (timeMs - sectionStartMs) / 1000.0
        val avg = if (inSection != null && elapsedS > 0) sectionMeters / elapsedS * 3.6 else null
        val remaining = inSection?.sectionLengthM?.let { (it - sectionMeters).coerceAtLeast(0.0) }
        val safe = if (inSection?.sectionLengthM != null && remaining != null) {
            sectionSafeKmh(inSection.sectionLengthM.toDouble(), inSection.limitKmh, remaining, elapsedS)
        } else null
        status = CameraStatus(ahead?.first, ahead?.second, avg, inSection?.limitKmh, remaining, safe, positionSource)
        return alerts
    }

    private fun onPassed(cam: SpeedCamera, timeMs: Long, alerts: MutableList<CameraAlert>) {
        when (cam.section) {
            SectionRole.START -> {
                section = cam
                sectionStartMs = timeMs
                sectionMeters = 0.0
                alerts += CameraAlert.SectionStart(cam, 0.0)
            }
            SectionRole.END -> {
                val active = section
                val seconds = (timeMs - sectionStartMs) / 1000.0
                val avg = if (active != null && seconds > 0) sectionMeters / seconds * 3.6 else null
                alerts += CameraAlert.SectionEnd(cam, 0.0, avg)
                section = null
            }
            null -> Unit
        }
    }

    private fun onOtherRoad(c: SpeedCamera, roadLimitKmh: Int?): Boolean =
        roadLimitKmh != null && roadLimitKmh > 0 && c.zone == null && abs(c.limitKmh - roadLimitKmh) >= ROAD_MISMATCH_KMH

    private fun trackHeading(lat: Double, lon: Double): Double? {
        val from = track.lastOrNull { Geo.distanceM(it.lat, it.lon, lat, lon) >= MIN_TRACK_M } ?: return null
        return Geo.bearing(from.lat, from.lon, lat, lon)
    }

    private fun curvature(lat: Double, lon: Double, heading: Double): Double {
        val from = track.firstOrNull { it.heading != null } ?: return 0.0
        val dist = Geo.distanceM(from.lat, from.lon, lat, lon)
        if (dist < MIN_TRACK_M) return 0.0
        return (Math.toRadians(Geo.signedAngle(from.heading!!, heading)) / dist).coerceIn(-MAX_CURVATURE, MAX_CURVATURE)
    }

    private fun onPath(lat: Double, lon: Double, heading: Double, curvature: Double, c: SpeedCamera, d: Double): Boolean {
        val angle = Math.toRadians(Geo.signedAngle(heading, Geo.bearing(lat, lon, c.latitude, c.longitude)))
        val along = d * cos(angle)
        if (along <= 0) return false
        val bend = curvature * along
        if (abs(bend) > 0.6) return abs(d * sin(angle)) <= sideM + along * 0.12
        val side = d * sin(angle) - curvature * along * along / 2
        return abs(side) <= sideM + along * 0.05
    }

    private fun rememberDistances(candidates: List<Pair<SpeedCamera, Double>>) {
        val ids = candidates.map { it.first.id }.toSet()
        recentDistance.keys.retainAll(ids)
        candidates.forEach { (c, d) ->
            val q = recentDistance.getOrPut(c.id) { ArrayDeque() }
            q.addLast(d)
            while (q.size > CLOSING_FIXES) q.removeFirst()
        }
    }

    private fun closing(id: String): Boolean {
        val q = recentDistance[id] ?: return false
        return q.size >= 2 && q.last() < q.first() - 1.0
    }

    private data class Fix(val lat: Double, val lon: Double, val heading: Double?, val timeMs: Long)

    companion object {
        private const val TRACK_FIXES = 8
        private const val MIN_TRACK_M = 25.0
        private const val CLOSING_FIXES = 3
        private const val MAX_CURVATURE = 1 / 30.0
        private const val MIN_MOVING_KMH = 8.0
        private const val PASS_NEAR_M = 80.0

        /** Positions further apart in time (car GPS over BLE arrives every few seconds) break the track. */
        const val MAX_GAP_MS = 15_000L
        const val MAX_JUMP_M = 1_000.0

        /** Car map limit and camera limit this far apart: the camera is on another road. */
        const val ROAD_MISMATCH_KMH = 30

        fun sectionSafeKmh(lengthM: Double, limitKmh: Int, remainingM: Double, elapsedS: Double): Double? {
            if (remainingM <= 0) return null
            val budgetS = lengthM / (limitKmh / 3.6) - elapsedS
            if (budgetS <= 0) return null
            return (remainingM / budgetS * 3.6).coerceAtMost(limitKmh * 1.5)
        }
    }
}

/** Distance as shown and spoken: "350m", "1.2km". */
fun cameraDistanceText(m: Double): String = if (m >= 1000) "%.1fkm".format(m / 1000) else "${(m / 10).roundToInt() * 10}m"

/** Short kind label for the screen. */
val SpeedCamera.kindLabel: String
    get() = when {
        section == SectionRole.START -> "구간단속 시작"
        section == SectionRole.END -> "구간단속 종료"
        zone != null -> zone
        signal -> "신호·과속"
        else -> "과속카메라"
    }

/** Spoken warning, kept here so it is testable on the JVM. */
fun CameraAlert.spokenText(): String {
    val limit = camera.limitKmh
    val zone = camera.zone?.let { "$it " }.orEmpty()
    val kind = if (camera.signal) "신호 과속" else "과속"
    return when (this) {
        is CameraAlert.Approaching ->
            "${(distanceM / 50).roundToInt() * 50}미터 앞 ${zone}$kind 카메라, 제한속도 $limit" + if (overLimit) ". 속도를 줄이세요" else ""
        is CameraAlert.Final ->
            "${(distanceM / 10).roundToInt() * 10}미터 앞 ${zone}단속 카메라, 제한속도 $limit" + if (overLimit) ". 속도를 줄이세요" else ""
        is CameraAlert.SectionStart -> "구간단속 시작, 제한속도 $limit"
        is CameraAlert.SectionEnd -> averageKmh?.let { "구간단속 종료, 평균 속도 ${it.roundToInt()}킬로미터" } ?: "구간단속 종료"
    }
}
