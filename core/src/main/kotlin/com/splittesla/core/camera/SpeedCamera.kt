package com.splittesla.core.camera

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a camera sits in a section (average speed) enforcement zone. */
enum class SectionRole { START, END }

/** One fixed enforcement camera from the national standard dataset. */
data class SpeedCamera(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val limitKmh: Int,
    val place: String,
    val section: SectionRole? = null,
    val sectionLengthM: Int? = null,
    /** "어린이보호구역" and the like, when the camera guards a protected zone. */
    val zone: String? = null,
    /** The dataset says it watches both directions. */
    val twoWay: Boolean = false,
)

internal object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, 0..360 with 0 = north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Turn from heading [from] to heading [to], -180..180, positive clockwise (to the right). */
    fun signedAngle(from: Double, to: Double): Double = ((to - from) % 360 + 540) % 360 - 180

    /** Smallest angle between two headings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360 + 360) % 360
        return if (d > 180) 360 - d else d
    }
}

/** Buckets cameras into ~1 km grid cells so a lookup only scans nearby ones. */
class CameraIndex(cameras: List<SpeedCamera>) {
    private val cells: Map<Long, List<SpeedCamera>> = cameras.groupBy { key(cell(it.latitude), cell(it.longitude)) }
    val size: Int = cameras.size

    fun near(lat: Double, lon: Double, radiusM: Double): List<SpeedCamera> {
        val r = (radiusM / 1000.0 / CELL_KM).toInt() + 1
        val cy = cell(lat)
        val cx = cell(lon)
        val out = ArrayList<SpeedCamera>()
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) cells[key(y, x)]?.let(out::addAll)
        return out.filter { Geo.distanceM(lat, lon, it.latitude, it.longitude) <= radiusM }
    }

    private fun cell(deg: Double) = Math.floor(deg / CELL_DEG).toInt()
    private fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

    private companion object {
        const val CELL_DEG = 0.01
        const val CELL_KM = 0.88 // smallest cell edge in Korea (longitude at 38°N)
    }
}
