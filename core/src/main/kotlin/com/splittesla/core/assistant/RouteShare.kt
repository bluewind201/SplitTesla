package com.splittesla.core.assistant

/**
 * Decides when the car's destination has changed enough to send it to the
 * phone's navigation app again. The car reports the same destination with
 * slightly different coordinates from one read to the next (it snaps to the
 * road), so small moves are the same place.
 */
object RouteShare {
    const val SAME_PLACE_M = 150.0

    fun isNewDestination(lastSent: LatLon?, now: LatLon): Boolean =
        lastSent == null || Geo.distanceKm(lastSent, now) * 1000 > SAME_PLACE_M
}
