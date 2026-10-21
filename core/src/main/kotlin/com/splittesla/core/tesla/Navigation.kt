package com.splittesla.core.tesla

import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle

private const val KM_PER_MILE = 1.609344

/**
 * The car's active route and position. The official BLE protocol can read the
 * route the driver set on the car's screen but cannot set a destination;
 * sending one is a Fleet API feature.
 */
data class NavigationInfo(
    val destination: String?,
    val minutesToArrival: Double?,
    val kmToArrival: Double?,
    val trafficDelayMinutes: Double?,
    val energyAtArrival: Double?,
    val destinationLat: Double?,
    val destinationLon: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val heading: Int?,
    val locationName: String?,
    val speedKmh: Double?,
) {
    val hasRoute: Boolean get() = destination != null || minutesToArrival != null

    companion object {
        fun from(data: Vehicle.VehicleData): NavigationInfo {
            val drive = data.driveState
            val loc = data.locationState
            val dest = drive.activeRouteCoordinates.takeIf { drive.hasActiveRouteCoordinates() }
            return NavigationInfo(
                destination = drive.activeRouteDestination.takeIf { drive.hasActiveRouteDestination() && it.isNotBlank() },
                minutesToArrival = drive.activeRouteMinutesToArrival.toDouble().takeIf { drive.hasActiveRouteMinutesToArrival() },
                kmToArrival = (drive.activeRouteMilesToArrival * KM_PER_MILE).takeIf { drive.hasActiveRouteMilesToArrival() },
                trafficDelayMinutes = drive.activeRouteTrafficMinutesDelay.toDouble().takeIf { drive.hasActiveRouteTrafficMinutesDelay() },
                energyAtArrival = drive.activeRouteEnergyAtArrival.toDouble().takeIf { drive.hasActiveRouteEnergyAtArrival() },
                destinationLat = dest?.latitude?.toDouble(),
                destinationLon = dest?.longitude?.toDouble(),
                latitude = loc.latitude.toDouble().takeIf { loc.hasLatitude() },
                longitude = loc.longitude.toDouble().takeIf { loc.hasLongitude() },
                heading = loc.heading.takeIf { loc.hasHeading() },
                locationName = loc.locationName.takeIf { loc.hasLocationName() && it.isNotBlank() },
                speedKmh = (drive.speedFloat * KM_PER_MILE).takeIf { drive.hasSpeedFloat() },
            )
        }
    }
}

/** A Supercharger near the car, as the car's own trip planner sees it. */
data class ChargingSite(
    val name: String,
    val address: String,
    val distanceKm: Double,
    val availableStalls: Int,
    val totalStalls: Int,
    val maxPowerKw: Int,
    val latitude: Double,
    val longitude: Double,
    val withinRange: Boolean,
    val closed: Boolean,
) {
    companion object {
        fun from(s: CarServer.Superchargers) = ChargingSite(
            name = s.name,
            address = listOf(s.city, s.district, s.streetAddress).filter { it.isNotBlank() }.joinToString(" "),
            distanceKm = s.distanceMiles * KM_PER_MILE,
            availableStalls = s.availableStalls,
            totalStalls = s.totalStalls,
            maxPowerKw = s.maxPowerKw,
            latitude = s.location.latitude.toDouble(),
            longitude = s.location.longitude.toDouble(),
            withinRange = s.withinRange,
            closed = s.siteClosed,
        )
    }
}
