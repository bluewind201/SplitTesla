package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon

/** A fast charger that could become a stop: a Supercharger from the car or a public one. */
data class ChargerSpot(
    val name: String,
    val point: LatLon,
    val tesla: Boolean,
    val powerKw: Int,
    val available: Int,
    val total: Int,
    val operator: String = "",
    val address: String = "",
)

/** A charger ranked for the active route. */
data class ChargeStop(
    val spot: ChargerSpot,
    val alongM: Double,
    val detourM: Double,
    /** Battery percent left when reaching it. */
    val socOnArrival: Double?,
)

/**
 * Picks charging stops along the route when the battery would arrive below
 * the reserve. A stop must lie close to the line (one-way detour under
 * [maxDetourM]), be reachable with [minSocOnArrival] to spare and have a free
 * stall. Superchargers come first, then more power, then the later stop
 * (charging when the battery is lower is faster).
 */
object ChargePlanner {
    fun plan(
        tracker: RouteTracker,
        fromAlongM: Double,
        spots: List<ChargerSpot>,
        socNow: Double?,
        fullKwh: Double?,
        factor: Double,
        outsideC: Double?,
        maxDetourM: Double = 2500.0,
        minSocOnArrival: Double = 7.0,
        limit: Int = 5,
    ): List<ChargeStop> {
        val geo = tracker.geometry
        val full = fullKwh?.takeIf { it > 20 } ?: EnergyModel.DEFAULT_PACK_KWH
        val avgWhPerKm = averageWhPerKm(tracker, outsideC) * factor
        return spots.asSequence()
            .filter { it.total == 0 || it.available > 0 }
            .mapNotNull { s ->
                val hit = geo.project(s.point) ?: return@mapNotNull null
                if (hit.alongM < fromAlongM + 1000 || hit.lateralM > maxDetourM) return@mapNotNull null
                val soc = socNow?.let { it - (hit.alongM - fromAlongM) / 1000.0 * avgWhPerKm / 1000.0 / full * 100.0 }
                if (soc != null && soc < minSocOnArrival) return@mapNotNull null
                ChargeStop(s, hit.alongM, hit.lateralM, soc)
            }
            .sortedWith(
                compareByDescending<ChargeStop> { it.spot.tesla }
                    .thenByDescending { it.spot.powerKw >= 100 }
                    .thenBy { it.detourM > 800 }
                    .thenByDescending { it.alongM },
            )
            .take(limit)
            .toList()
    }

    private fun averageWhPerKm(tracker: RouteTracker, outsideC: Double?): Double {
        val r = tracker.route
        val v = (r.distanceM / 1000.0) / (r.durationMs / 3_600_000.0).coerceAtLeast(1e-6)
        return EnergyModel.whPerKm(v, outsideC)
    }

    /** Points along the route worth searching for public chargers: where the battery gets low. */
    fun searchPoints(tracker: RouteTracker, fromAlongM: Double, count: Int = 3): List<LatLon> {
        val geo = tracker.geometry
        val start = fromAlongM + (geo.lengthM - fromAlongM) * 0.35
        val end = fromAlongM + (geo.lengthM - fromAlongM) * 0.85
        if (count <= 1) return listOf(geo.pointAt((start + end) / 2))
        return (0 until count).map { geo.pointAt(start + (end - start) * it / (count - 1)) }
    }
}
