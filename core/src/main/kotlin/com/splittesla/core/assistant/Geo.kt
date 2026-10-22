package com.splittesla.core.assistant

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_KM = 6371.0

    fun distanceKm(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val h = sin(dLat / 2).let { it * it } +
            cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * True when [p] lies ahead of [from] in a band around the straight line to
     * [to]. Roads bend, so the band widens with trip length: 15% of the trip,
     * at least [minWidthKm] either side.
     */
    fun aheadOnRoute(from: LatLon, to: LatLon, p: LatLon, minWidthKm: Double = 3.0): Boolean {
        // Flat projection around the start; fine for the distances a drive covers.
        val kx = cos(from.lat * PI / 180) * 111.32
        val ky = 110.57
        val bx = (to.lon - from.lon) * kx
        val by = (to.lat - from.lat) * ky
        val px = (p.lon - from.lon) * kx
        val py = (p.lat - from.lat) * ky
        val len2 = bx * bx + by * by
        val trip = sqrt(len2)
        val width = max(minWidthKm, trip * 0.15)
        if (len2 < 1e-9) return sqrt(px * px + py * py) <= width
        val t = (px * bx + py * by) / len2
        if (t < 0 || t > 1.05) return false
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy) <= width
    }
}
