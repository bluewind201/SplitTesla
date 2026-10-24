package com.splittesla.core.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What the driver should hear. */
sealed class CameraAlert {
    abstract val camera: SpeedCamera
    abstract val distanceM: Double

    /** Early warning; how early depends on speed. */
    data class Approaching(override val camera: SpeedCamera, override val distanceM: Double, val overLimit: Boolean) : CameraAlert()

    /** The 100 m warning. */
    data class Final(override val camera: SpeedCamera, override val distanceM: Double, val overLimit: Boolean) : CameraAlert()

    /** Entered a section (average speed) enforcement zone. */
    data class SectionStart(override val camera: SpeedCamera, override val distanceM: Double) : CameraAlert()

    /** Left the section; [averageKmh] is what the cameras will have measured. */
    data class SectionEnd(override val camera: SpeedCamera, override val distanceM: Double, val averageKmh: Double) : CameraAlert()
}

/** Live view for the screen. */
data class CameraStatus(
    val ahead: SpeedCamera? = null,
    val distanceM: Double? = null,
    val sectionAverageKmh: Double? = null,
    val sectionLimitKmh: Int? = null,
    /** Metres left to the section's end camera, when the section length is known. */
    val sectionRemainingM: Double? = null,
    /** Highest steady speed for the rest of the section that keeps the average at the limit. */
    val sectionSafeKmh: Double? = null,
)

/**
 * Decides when to warn about cameras from a stream of GPS fixes, the way
 * navigation apps do without a map. The dataset has no enforcement direction,
 * so direction is inferred from where the camera sits relative to the path we
 * are about to drive (our heading, bent by how fast we are turning):
 *
 * - Korea drives on the right and camera poles stand on the right of the
 *   traffic they watch, so a camera more than [leftM] to our left belongs to
 *   the opposite carriageway, unless the dataset marks it two-way.
 * - On the right it may be up to [rightM] (plus a little per metre ahead)
 *   away; further than that it is on a side road.
 * - The distance to it must be shrinking across recent fixes.
 *
 * Each camera warns once early and once at [finalM].
 */
class CameraAlerter(
    private val index: CameraIndex,
    private val finalM: Double = 100.0,
    private val leftM: Double = 10.0,
    private val rightM: Double = 25.0,
) {
    private val warnedEarly = HashSet<String>()
    private val warnedFinal = HashSet<String>()
    private var lastAhead: SpeedCamera? = null
    private var lastDistance = Double.MAX_VALUE

    private var section: SpeedCamera? = null
    private var sectionStartMs = 0L
    private var sectionMeters = 0.0
    private var lastFix: Pair<Double, Double>? = null
    private val track = ArrayDeque<Fix>()
    private val recentDistance = HashMap<String, ArrayDeque<Double>>()

    var status = CameraStatus()
        private set

    /** Early warning distance: about 12 s of travel, 200–600 m. */
    fun earlyDistanceM(speedKmh: Double): Double = (speedKmh / 3.6 * 12).coerceIn(200.0, 600.0)

    /**
     * Feeds one fix. [headingDeg] is the GPS course; it is only trusted while
     * moving faster than walking pace.
     */
    fun update(lat: Double, lon: Double, headingDeg: Double?, speedKmh: Double, timeMs: Long): List<CameraAlert> {
        val alerts = ArrayList<CameraAlert>()
        lastFix?.let { (pLat, pLon) -> if (section != null) sectionMeters += Geo.distanceM(pLat, pLon, lat, lon) }
        lastFix = lat to lon

        val early = earlyDistanceM(speedKmh)
        // Doppler course from the GPS chip is good once moving; at low speed our own track is steadier.
        val heading = (if (speedKmh >= 20) headingDeg else null) ?: trackHeading(lat, lon) ?: headingDeg
        val curvature = heading?.let { curvature(lat, lon, it) } ?: 0.0
        track.addLast(Fix(lat, lon, heading))
        while (track.size > TRACK_FIXES) track.removeFirst()

        val moving = heading != null && speedKmh >= 10
        val candidates = if (!moving) emptyList() else index.near(lat, lon, early + 50)
            .map { it to Geo.distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { (c, d) -> onPath(lat, lon, heading!!, curvature, c, d) || (d < 30 && c.id == lastAhead?.id) }
        rememberDistances(candidates)
        // Once it is beside us the distance stops shrinking; keep it so passing is noticed.
        val ahead = candidates.filter { (c, d) -> closing(c.id) || (d < 30 && c.id == lastAhead?.id) }.minByOrNull { it.second }

        // The camera we were closing in on is now behind us: it was passed.
        val passed = lastAhead?.takeIf { prev -> ahead?.first?.id != prev.id && lastDistance < 80 }
        if (passed != null) onPassed(passed, timeMs, alerts)

        if (ahead != null) {
            val (cam, d) = ahead
            val over = speedKmh > cam.limitKmh
            if (d <= finalM && warnedFinal.add(cam.id)) {
                warnedEarly.add(cam.id)
                alerts += CameraAlert.Final(cam, d, over)
            } else if (d <= early && d > finalM && warnedEarly.add(cam.id)) {
                alerts += CameraAlert.Approaching(cam, d, over)
            }
            lastAhead = cam
            lastDistance = d
        } else {
            lastAhead = null
            lastDistance = Double.MAX_VALUE
        }

        // Forget cameras that are well behind so a return trip warns again.
        if (warnedEarly.size > 50) {
            val nearby = index.near(lat, lon, 1500.0).map { it.id }.toSet()
            warnedEarly.retainAll(nearby)
            warnedFinal.retainAll(nearby)
        }

        val s = section
        val avg = if (s != null && timeMs > sectionStartMs) sectionMeters / ((timeMs - sectionStartMs) / 1000.0) * 3.6 else null
        if (s != null && sectionMeters > (s.sectionLengthM ?: 20_000) + 2_000) section = null // missed the end camera
        val inSection = section
        val remaining = inSection?.sectionLengthM?.let { (it - sectionMeters).coerceAtLeast(0.0) }
        val safe = if (inSection != null && remaining != null) {
            sectionSafeKmh(inSection.sectionLengthM!!.toDouble(), inSection.limitKmh, remaining, (timeMs - sectionStartMs) / 1000.0)
        } else null
        status = CameraStatus(ahead?.first, ahead?.second, avg?.takeIf { inSection != null }, inSection?.limitKmh, remaining, safe)
        return alerts
    }

    /** Course over the last few fixes, once they span [MIN_TRACK_M]. */
    private fun trackHeading(lat: Double, lon: Double): Double? {
        val from = track.lastOrNull { Geo.distanceM(it.lat, it.lon, lat, lon) >= MIN_TRACK_M } ?: return null
        return Geo.bearing(from.lat, from.lon, lat, lon)
    }

    /**
     * How fast we are turning, in radians per metre, positive to the right,
     * from the heading change over the last stretch of road. Zero until we
     * have moved [MIN_TRACK_M].
     */
    private fun curvature(lat: Double, lon: Double, heading: Double): Double {
        val from = track.firstOrNull { it.heading != null } ?: return 0.0
        val dist = Geo.distanceM(from.lat, from.lon, lat, lon)
        if (dist < MIN_TRACK_M) return 0.0
        return (Math.toRadians(Geo.signedAngle(from.heading!!, heading)) / dist).coerceIn(-MAX_CURVATURE, MAX_CURVATURE)
    }

    /**
     * Ahead of us, on our side of the road, and close to the path we are
     * about to drive. [curvature] bends that path, so a camera on our own
     * carriageway through a long bend is not mistaken for one across the road.
     */
    private fun onPath(lat: Double, lon: Double, heading: Double, curvature: Double, c: SpeedCamera, d: Double): Boolean {
        val angle = Math.toRadians(Geo.signedAngle(heading, Geo.bearing(lat, lon, c.latitude, c.longitude)))
        val along = d * cos(angle)
        if (along <= 0) return false
        val bend = curvature * along
        // Too sharp a turn to predict the road that far: only drop what is clearly off to the side.
        if (abs(bend) > 0.6) return abs(d * sin(angle)) <= rightM + along * 0.12
        val right = d * sin(angle) - curvature * along * along / 2
        // Heading and curvature errors grow with distance, so allow a little more far out.
        val slack = along * 0.03
        val leftLimit = if (c.twoWay) rightM + slack else leftM + slack
        return right >= -leftLimit && right <= rightM + slack * 2
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

    /**
     * The distance has fallen across the recent fixes. Cameras are tracked
     * from [earlyDistanceM] + 50 m, so in normal driving there are already
     * a few samples by the time the early warning is due.
     */
    private fun closing(id: String): Boolean {
        val q = recentDistance[id] ?: return false
        return q.size >= 2 && q.last() < q.first() - 1.0
    }

    companion object {
        private const val TRACK_FIXES = 8
        private const val MIN_TRACK_M = 25.0
        private const val CLOSING_FIXES = 3
        private const val MAX_CURVATURE = 1 / 30.0

        /**
         * Highest steady speed for the [remainingM] left of a [lengthM] section
         * that keeps the overall average at [limitKmh], having spent
         * [elapsedS] so far. Null when the budget is already gone.
         */
        fun sectionSafeKmh(lengthM: Double, limitKmh: Int, remainingM: Double, elapsedS: Double): Double? {
            if (remainingM <= 0) return null
            val budgetS = lengthM / (limitKmh / 3.6) - elapsedS
            if (budgetS <= 0) return null
            return (remainingM / budgetS * 3.6).coerceAtMost(limitKmh * 1.5)
        }
    }

    private data class Fix(val lat: Double, val lon: Double, val heading: Double?)

    private fun onPassed(cam: SpeedCamera, timeMs: Long, alerts: MutableList<CameraAlert>) {
        when (cam.section) {
            SectionRole.START -> {
                section = cam
                sectionStartMs = timeMs
                sectionMeters = 0.0
                alerts += CameraAlert.SectionStart(cam, 0.0)
            }
            SectionRole.END -> {
                if (section != null) {
                    val seconds = (timeMs - sectionStartMs) / 1000.0
                    val avg = if (seconds > 0) sectionMeters / seconds * 3.6 else 0.0
                    alerts += CameraAlert.SectionEnd(cam, 0.0, avg)
                }
                section = null
            }
            null -> Unit
        }
    }
}
