package com.splittesla.core.camera

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
 * navigation apps do without a map: a camera counts only when it lies ahead
 * on the line we are driving along. Its sideways offset from that line must
 * stay small ([lateralM] plus a little per metre ahead, to allow for bends),
 * which drops cameras on side roads and parallel streets that a plain angle
 * cone would catch, and the distance to it must be shrinking across recent
 * fixes. Heading comes from our own track once we have moved far enough,
 * which is steadier than the per-fix GPS course. Each camera warns once
 * early and once at [finalM].
 */
class CameraAlerter(
    private val index: CameraIndex,
    private val finalM: Double = 100.0,
    private val lateralM: Double = 25.0,
    private val lateralPerM: Double = 0.12,
) {
    private val warnedEarly = HashSet<String>()
    private val warnedFinal = HashSet<String>()
    private var lastAhead: SpeedCamera? = null
    private var lastDistance = Double.MAX_VALUE

    private var section: SpeedCamera? = null
    private var sectionStartMs = 0L
    private var sectionMeters = 0.0
    private var lastFix: Pair<Double, Double>? = null
    private val track = ArrayDeque<Pair<Double, Double>>()
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

        track.addLast(lat to lon)
        while (track.size > TRACK_FIXES) track.removeFirst()

        val early = earlyDistanceM(speedKmh)
        val heading = smoothedHeading(lat, lon) ?: headingDeg
        val moving = heading != null && speedKmh >= 10
        val candidates = if (!moving) emptyList() else index.near(lat, lon, early + 50)
            .map { it to Geo.distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { (c, d) -> d < 30 || onPath(lat, lon, heading!!, c, d) }
        rememberDistances(candidates)
        val ahead = candidates.filter { (c, d) -> d < 30 || closing(c.id) }.minByOrNull { it.second }

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

    /**
     * Course over the last few fixes, once they span [MIN_TRACK_M]. GPS course
     * from a single fix wobbles at low speed and near buildings.
     */
    private fun smoothedHeading(lat: Double, lon: Double): Double? {
        val from = track.toList().dropLast(1).lastOrNull { (pLat, pLon) -> Geo.distanceM(pLat, pLon, lat, lon) >= MIN_TRACK_M } ?: return null
        return Geo.bearing(from.first, from.second, lat, lon)
    }

    /** Ahead of us and close to the line we are driving along. */
    private fun onPath(lat: Double, lon: Double, heading: Double, c: SpeedCamera, d: Double): Boolean {
        val angle = Math.toRadians(Geo.angleDiff(Geo.bearing(lat, lon, c.latitude, c.longitude), heading))
        val along = d * kotlin.math.cos(angle)
        val side = d * kotlin.math.sin(angle)
        return along > 0 && side <= lateralM + along * lateralPerM
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
