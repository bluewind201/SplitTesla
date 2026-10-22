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
)

/**
 * Decides when to warn about cameras from a stream of GPS fixes. A camera
 * counts only when it lies ahead (within [coneDeg] of the direction of
 * travel), which filters out cameras on the opposite carriageway in most
 * cases. Each camera warns once early and once at [finalM].
 */
class CameraAlerter(
    private val index: CameraIndex,
    private val finalM: Double = 100.0,
    private val coneDeg: Double = 30.0,
) {
    private val warnedEarly = HashSet<String>()
    private val warnedFinal = HashSet<String>()
    private var lastAhead: SpeedCamera? = null
    private var lastDistance = Double.MAX_VALUE

    private var section: SpeedCamera? = null
    private var sectionStartMs = 0L
    private var sectionMeters = 0.0
    private var lastFix: Pair<Double, Double>? = null

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
        val moving = headingDeg != null && speedKmh >= 10
        val ahead = if (!moving) null else index.near(lat, lon, early + 50)
            .map { it to Geo.distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { (c, d) -> d < 30 || Geo.angleDiff(Geo.bearing(lat, lon, c.latitude, c.longitude), headingDeg!!) <= coneDeg }
            .minByOrNull { it.second }

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
        status = CameraStatus(ahead?.first, ahead?.second, avg?.takeIf { section != null }, section?.limitKmh)
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
