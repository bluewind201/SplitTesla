package com.splittesla.core.camera

import com.splittesla.core.assistant.LatLon
import com.splittesla.core.navi.FixGate
import com.splittesla.core.navi.SpeedEvidence
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

    /** Real END point passed. [averageKmh] is usable only when [averageVerified] is true. */
    data class SectionEnd(override val camera: SpeedCamera, override val distanceM: Double, val averageKmh: Double, val averageVerified: Boolean = true) : CameraAlert()
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
    /** Our heading when this was computed; recorded with "not for this direction". */
    val headingDeg: Double? = null,
)

/**
 * Decides when to warn about cameras from a stream of GPS fixes, the way
 * navigation apps do without a map: a camera counts when it lies ahead within
 * [sideM] (plus a little per metre ahead) of the path we are about to drive,
 * that path bent by how fast we are turning, and the distance to it is
 * shrinking across recent fixes.
 *
 * The public dataset gives no usable enforcement direction: both directions
 * are usually listed at one shared point, often the middle of a junction, so
 * which side of the road a point lies on says nothing. Cameras the driver has
 * marked as "not for this direction" are skipped through [ignored].
 *
 * Three more checks cut warnings for cameras on other roads:
 * - while guiding, [routeCameraIds] limits warnings to cameras on the route;
 * - the car's own map limit for the road (from the Commander) that differs
 *   from a camera's by [ROAD_MISMATCH_KMH] or more marks a camera on a road
 *   beside, above or across ours (protected-zone cameras are always kept);
 * - a camera that was announced but then slid off to the side while we drove
 *   straight on, never getting within [PASSED_NEAR_M], is reported through
 *   [onMiss]; one driven right past goes to [onDrivenPast]. The caller counts
 *   them and marks a camera [ignored] for that heading after repeated misses.
 *
 * Each camera warns once early and once at [finalM].
 */
class CameraAlerter(
    private val index: CameraIndex,
    private val finalM: Double = 100.0,
    private val sideM: Double = 30.0,
    private val ignored: (cameraId: String, headingDeg: Double) -> Boolean = { _, _ -> false },
    private val onMiss: (camera: SpeedCamera, headingDeg: Double) -> Unit = { _, _ -> },
    private val onDrivenPast: (camera: SpeedCamera, headingDeg: Double) -> Unit = { _, _ -> },
) {
    var warningPreset: CameraWarningPreset = CameraWarningPreset.SPEED
        set(value) {
            if (field != value) {
                // Changing settings never replays approach prompts for an already announced encounter.
                warnedEarly.forEach { warningStages.consume(it, listOf(1000.0, 600.0, 500.0, 300.0)) }
                field = value
            }
        }
    private val warningStages = CameraWarningStages()

    /** While guiding: the cameras on the route. Null warns for any camera on the road ahead. */
    var routeCameraIds: Set<String>? = null

    /** Heading when each camera was first announced, kept until it is passed or missed. */
    private val announcedHeading = HashMap<String, Double>()

    /** Closest we have been to each announced camera. */
    private val closestM = HashMap<String, Double>()

    private val warnedEarly = HashSet<String>()
    private val warnedFinal = HashSet<String>()
    private var lastAhead: SpeedCamera? = null
    private var lastDistance = Double.MAX_VALUE

    /** Only independently verified directional endpoint IDs may be supplied. Route order is insufficient. */
    var verifiedSectionEnds: Map<String, String> = emptyMap()
    private val fixGate = FixGate()
    private var averageValid = false
    private var section: SpeedCamera? = null
    private var sectionStartMs = 0L
    private var sectionMeters = 0.0
    private var lastFix: Pair<Double, Double>? = null
    private val passedCameras = HashSet<String>()
    private val track = ArrayDeque<Fix>()
    private val recentDistance = HashMap<String, ArrayDeque<Double>>()

    var status = CameraStatus()
        private set

    var lastFixAccepted: Boolean = false
        private set

    fun invalidateMeasurement() {
        lastFixAccepted = false
        averageValid = false
        status = status.copy(sectionAverageKmh = null, sectionSafeKmh = null, sectionRemainingM = null)
    }

    /** Early warning distance: about 12 s of travel, 200–600 m. */
    fun earlyDistanceM(speedKmh: Double): Double = (speedKmh / 3.6 * 12).coerceIn(200.0, 600.0)

    /**
     * Feeds one fix. [headingDeg] is the GPS course; it is only trusted while
     * moving faster than walking pace. [roadLimitKmh] is the limit the car's
     * own map gives for the road being driven, when the Commander reports it.
     */
    fun update(lat: Double, lon: Double, headingDeg: Double?, speedKmh: Double, timeMs: Long, roadLimitKmh: Int? = null, measuredSpeedKmh: Double? = speedKmh, accuracyM: Double? = null): List<CameraAlert> {
        val alerts = ArrayList<CameraAlert>()
        lastFixAccepted = false
        val evidence = fixGate.accept(LatLon(lat, lon), timeMs, accuracyM)
        if (evidence == null) {
            invalidateMeasurement()
            return emptyList()
        }
        lastFixAccepted = true
        if (!evidence.continuous) {
            averageValid = false
            lastAhead = null
            lastDistance = Double.MAX_VALUE
            track.clear()
            recentDistance.clear()
            announcedHeading.clear()
            closestM.clear()
        }
        val previousFix = lastFix
        lastFix?.let { (pLat, pLon) ->
            if (section != null && evidence.continuous) sectionMeters += Geo.distanceM(pLat, pLon, lat, lon)
        }
        lastFix = lat to lon
        val movementSpeed = SpeedEvidence.measured(speedKmh) ?: evidence.derivedKmh ?: 0.0
        val early = if (warningPreset == CameraWarningPreset.LIMIT) 1000.0 else earlyDistanceM(movementSpeed)
        // Doppler course from the GPS chip is good once moving; at low speed our own track is steadier.
        val course = headingDeg?.takeIf { it.isFinite() }
        val heading = (if (movementSpeed >= 20) course else null) ?: trackHeading(lat, lon) ?: course
        val curvature = heading?.let { curvature(lat, lon, it) } ?: 0.0
        track.addLast(Fix(lat, lon, heading))
        while (track.size > TRACK_FIXES) track.removeFirst()

        val moving = heading != null && heading.isFinite() && movementSpeed >= 10
        val candidates = if (!moving) emptyList() else index.near(lat, lon, early + 50)
            .map { it to Geo.distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { (c, d) -> onPath(lat, lon, heading!!, curvature, c, d) || (d < 30 && c.id == lastAhead?.id) }
            .filter { (c, _) -> !ignored(c.id, heading!!) }
            .filter { (c, _) -> routeCameraIds?.contains(c.id) ?: true }
            .filter { (c, _) -> !onOtherRoad(c, roadLimitKmh) }
        rememberDistances(candidates)
        // Once it is beside us the distance stops shrinking; keep it so passing is noticed.
        val ahead = candidates.filter { (c, d) -> closing(c.id) || (d < 30 && c.id == lastAhead?.id) }.minByOrNull { it.second }

        // Use the actual accepted GPS segment, not the last sample's distance threshold.
        // A 2 s interval can straddle a camera without either endpoint falling within 40 m.
        val passed = lastAhead?.takeIf { cam ->
            evidence.continuous && moving && cam.id !in passedCameras && previousFix?.let { (pLat, pLon) ->
                val segment = com.splittesla.core.navi.RouteGeometry(listOf(LatLon(pLat, pLon), LatLon(lat, lon)))
                val hit = segment.project(LatLon(cam.latitude, cam.longitude))
                val course = Geo.bearing(pLat, pLon, lat, lon)
                segment.lengthM >= 1 && hit != null && hit.lateralM <= sideM &&
                    hit.alongM in 0.0..segment.lengthM &&
                    (Geo.distanceM(pLat, pLon, cam.latitude, cam.longitude) <= 1 ||
                        Geo.angleDiff(course, Geo.bearing(pLat, pLon, cam.latitude, cam.longitude)) <= 90) &&
                    (Geo.distanceM(lat, lon, cam.latitude, cam.longitude) <= 1 ||
                        Geo.angleDiff(course, Geo.bearing(lat, lon, cam.latitude, cam.longitude)) >= 90)
            } == true
        }
        if (passed != null && passedCameras.add(passed.id)) onPassed(passed, timeMs, alerts)
        lastAhead?.takeIf { prev -> ahead?.first?.id != prev.id }?.let { prev ->
            learn(prev, lat, lon, heading.takeIf { moving }, curvature)
        }

        if (ahead != null) {
            val (cam, d) = ahead
            val over = SpeedEvidence.measured(measuredSpeedKmh)?.let { it > cam.limitKmh } == true
            if (d <= finalM && warnedFinal.add(cam.id)) {
                warnedEarly.add(cam.id)
                warningStages.consume(cam.id, warningPreset.stages(cam.limitKmh, movementSpeed))
                alerts += CameraAlert.Final(cam, d, over)
            } else if (d > finalM && cam.id !in warnedFinal &&
                (if (warningPreset == CameraWarningPreset.SPEED) d <= early && warnedEarly.add(cam.id)
                 else warningStages.due(cam.id, warningPreset.stages(cam.limitKmh, movementSpeed), d))) {
                warnedEarly.add(cam.id)
                warningStages.due(cam.id, CameraWarningPreset.LIMIT.stages(cam.limitKmh, movementSpeed), d)
                alerts += CameraAlert.Approaching(cam, d, over)
            }
            if (cam.id in warnedEarly) {
                announcedHeading.getOrPut(cam.id) { heading!! }
                closestM[cam.id] = minOf(closestM[cam.id] ?: d, d)
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
            warningStages.retain(nearby)
            passedCameras.retainAll(nearby)
            announcedHeading.keys.retainAll(nearby)
            closestM.keys.retainAll(nearby)
        }

        val s = section
        val avg = if (s != null && averageValid && timeMs > sectionStartMs) sectionMeters / ((timeMs - sectionStartMs) / 1000.0) * 3.6 else null
        if (s != null && (sectionMeters > (s.sectionLengthM ?: 20_000) + 2_000 || timeMs - sectionStartMs > 1_200_000)) section = null // missed the end camera
        val inSection = section
        val remaining = inSection?.takeIf { averageValid }?.sectionLengthM?.let { (it - sectionMeters).coerceAtLeast(0.0) }
        val safe = if (inSection != null && remaining != null) {
            sectionSafeKmh(inSection.sectionLengthM!!.toDouble(), inSection.limitKmh, remaining, (timeMs - sectionStartMs) / 1000.0)
        } else null
        status = CameraStatus(ahead?.first, ahead?.second, avg?.takeIf { inSection != null }, inSection?.limitKmh, remaining, safe, heading)
        return alerts
    }

    /**
     * The camera we were heading for is no longer ahead. Driven right past it:
     * it applies here. Still moving the same way but it has slid off to the
     * side of our path without us getting near: it was on another road.
     * Anything else (a turn, a stop, a filter) says nothing about the camera.
     */
    private fun learn(cam: SpeedCamera, lat: Double, lon: Double, heading: Double?, curvature: Double) {
        val announcedAt = announcedHeading[cam.id] ?: return
        val closest = closestM[cam.id] ?: return
        if (closest <= PASSED_NEAR_M && heading != null &&
            Geo.angleDiff(heading, Geo.bearing(lat, lon, cam.latitude, cam.longitude)) >= 90) {
            forget(cam.id)
            onDrivenPast(cam, announcedAt)
            return
        }
        if (heading == null || Geo.angleDiff(heading, announcedAt) > STRAIGHT_ON_DEG) return
        val d = Geo.distanceM(lat, lon, cam.latitude, cam.longitude)
        if (onPath(lat, lon, heading, curvature, cam, d)) return
        // Already beside or behind us: we may have gone past it on a wide road, so no verdict.
        if (Geo.angleDiff(heading, Geo.bearing(lat, lon, cam.latitude, cam.longitude)) >= 90) return
        forget(cam.id)
        onMiss(cam, announcedAt)
    }

    private fun forget(id: String) {
        announcedHeading.remove(id)
        closestM.remove(id)
    }

    /**
     * The car's map puts this road's limit far from the camera's, so the
     * camera watches a road beside, above or across ours. Protected-zone
     * cameras are kept: the car's map often lacks the zone's lower limit.
     */
    private fun onOtherRoad(c: SpeedCamera, roadLimitKmh: Int?): Boolean =
        roadLimitKmh != null && c.zone == null && abs(c.limitKmh - roadLimitKmh) >= ROAD_MISMATCH_KMH

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
     * Ahead of us and close to the path we are about to drive. [curvature]
     * bends that path, so a camera through a long bend is not mistaken for
     * one on a side road.
     */
    private fun onPath(lat: Double, lon: Double, heading: Double, curvature: Double, c: SpeedCamera, d: Double): Boolean {
        val angle = Math.toRadians(Geo.signedAngle(heading, Geo.bearing(lat, lon, c.latitude, c.longitude)))
        val along = d * cos(angle)
        if (along <= 0) return false
        val bend = curvature * along
        // Too sharp a turn to predict the road that far: only drop what is clearly off to the side.
        if (abs(bend) > 0.6) return abs(d * sin(angle)) <= sideM + along * 0.12
        val side = d * sin(angle) - curvature * along * along / 2
        // Heading and curvature errors grow with distance, so allow a little more far out.
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

        /** Car map limit and camera limit this far apart: the camera is on another road. */
        const val ROAD_MISMATCH_KMH = 30

        /** Closer than this to an announced camera counts as driving past it. */
        const val PASSED_NEAR_M = 40.0

        /** Course change still counted as carrying straight on. */
        private const val STRAIGHT_ON_DEG = 25.0

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
                // Without independent pair metadata there is no active measured section to track.
                section = cam.takeIf { verifiedSectionEnds.containsKey(it.id) }
                sectionStartMs = timeMs
                sectionMeters = 0.0
                averageValid = verifiedSectionEnds.containsKey(cam.id)
                alerts += CameraAlert.SectionStart(cam, 0.0)
            }
            SectionRole.END -> {
                val active = section
                val expected = active?.let { verifiedSectionEnds[it.id] }
                if (expected == null || expected == cam.id) {
                    val seconds = (timeMs - sectionStartMs) / 1000.0
                    val verified = active != null && expected == cam.id && averageValid && seconds > 0
                    val avg = if (verified) sectionMeters / seconds * 3.6 else 0.0
                    alerts += CameraAlert.SectionEnd(cam, 0.0, avg, averageVerified = verified)
                    // Clear a matching measured section, never infer a pair from this END.
                    section = null
                }
            }
            null -> Unit
        }
    }
}
