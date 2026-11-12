package com.splittesla.core.logbook

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One reading of the car while it may be driving. Any field may be unknown. */
data class DriveSample(
    val timeMillis: Long,
    /** "P", "R", "N", "D" or null. */
    val gear: String?,
    val speedKmh: Double?,
    val odometerKm: Double?,
    val socPercent: Int?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** District or place name for this spot, such as "분당구 정자동". */
    val place: String? = null,
    /** Nominal full pack, kWh, when the Commander reports it. */
    val packKwh: Double? = null,
    /** Pack energy left, kWh, when the Commander reports it. */
    val remainingKwh: Double? = null,
)

/** A trip from moving off to parking. */
data class DriveSession(
    val startMillis: Long,
    val endMillis: Long,
    val distanceKm: Double,
    val startOdometerKm: Double?,
    val endOdometerKm: Double?,
    val socStart: Int?,
    val socEnd: Int?,
    /** Energy used: measured pack energy when known, else battery % × pack size. */
    val kwh: Double?,
    val startPlace: String?,
    val endPlace: String?,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
) {
    val kmPerKwh: Double? get() = kwh?.takeIf { it > 0.05 }?.let { distanceKm / it }
    val whPerKm: Double? get() = kwh?.takeIf { distanceKm > 0.1 }?.let { it * 1000 / distanceKm }
    val minutes: Long get() = (endMillis - startMillis) / 60_000
}

/**
 * Turns a stream of car readings into trips. A trip starts when the car is
 * in D or R, or moving, and ends once it is in P (or has stood still for
 * [IDLE_END_MS] with no gear known). Distance comes from the odometer when
 * both ends have one, else from the GPS track.
 */
class DriveSessionTracker(private val defaultPackKwh: Double = DEFAULT_PACK_KWH) {
    data class Pending(
        val startMillis: Long,
        val startOdometerKm: Double?,
        val socStart: Int?,
        val startPlace: String?,
        val startLat: Double?,
        val startLon: Double?,
        val startRemainingKwh: Double?,
        val gpsKm: Double = 0.0,
        val lastLat: Double? = null,
        val lastLon: Double? = null,
        val lastMoveMillis: Long = 0,
        val lastOdometerKm: Double? = null,
        val lastSoc: Int? = null,
        val lastPlace: String? = null,
        val lastRemainingKwh: Double? = null,
        val packKwh: Double? = null,
    )

    var current: Pending? = null
        private set

    fun feed(s: DriveSample): DriveSession? {
        val moving = (s.speedKmh ?: 0.0) >= MOVING_KMH
        val inGear = s.gear == "D" || s.gear == "R"
        val p = current
        if (p == null) {
            if (moving || inGear) {
                current = Pending(
                    startMillis = s.timeMillis, startOdometerKm = s.odometerKm, socStart = s.socPercent,
                    startPlace = s.place, startLat = s.latitude, startLon = s.longitude, startRemainingKwh = s.remainingKwh,
                    lastLat = s.latitude, lastLon = s.longitude, lastMoveMillis = s.timeMillis,
                    lastOdometerKm = s.odometerKm, lastSoc = s.socPercent, lastPlace = s.place,
                    lastRemainingKwh = s.remainingKwh, packKwh = s.packKwh,
                )
            }
            return null
        }
        var gps = p.gpsKm
        if (s.latitude != null && s.longitude != null && p.lastLat != null && p.lastLon != null) {
            val step = distanceKm(p.lastLat, p.lastLon, s.latitude, s.longitude)
            // A jump this large in one sample is a bad fix, not driving.
            if (step < 5.0) gps += step
        }
        val next = p.copy(
            startOdometerKm = p.startOdometerKm ?: s.odometerKm,
            socStart = p.socStart ?: s.socPercent,
            startPlace = p.startPlace ?: s.place,
            startLat = p.startLat ?: s.latitude,
            startLon = p.startLon ?: s.longitude,
            startRemainingKwh = p.startRemainingKwh ?: s.remainingKwh,
            gpsKm = gps,
            lastLat = s.latitude ?: p.lastLat,
            lastLon = s.longitude ?: p.lastLon,
            lastMoveMillis = if (moving || inGear) s.timeMillis else p.lastMoveMillis,
            lastOdometerKm = s.odometerKm ?: p.lastOdometerKm,
            lastSoc = s.socPercent ?: p.lastSoc,
            lastPlace = s.place ?: p.lastPlace,
            lastRemainingKwh = s.remainingKwh ?: p.lastRemainingKwh,
            packKwh = s.packKwh ?: p.packKwh,
        )
        val parked = s.gear == "P" || (s.gear == null && !moving && s.timeMillis - next.lastMoveMillis >= IDLE_END_MS)
        if (!parked) {
            current = next
            return null
        }
        current = null
        return finish(next, s.timeMillis)
    }

    /** Ends a trip now, as when the car link drops for good or a charge starts. */
    fun end(nowMillis: Long): DriveSession? {
        val p = current ?: return null
        current = null
        return finish(p, nowMillis)
    }

    private fun finish(p: Pending, endMillis: Long): DriveSession? {
        val odo = if (p.startOdometerKm != null && p.lastOdometerKm != null) p.lastOdometerKm - p.startOdometerKm else null
        val distance = odo?.takeIf { it >= 0 } ?: p.gpsKm
        if (distance < MIN_KM) return null
        val measured = if (p.startRemainingKwh != null && p.lastRemainingKwh != null) p.startRemainingKwh - p.lastRemainingKwh else null
        val bySoc = if (p.socStart != null && p.lastSoc != null) (p.socStart - p.lastSoc) / 100.0 * (p.packKwh ?: defaultPackKwh) else null
        val kwh = (measured?.takeIf { it > 0 } ?: bySoc)?.takeIf { it > 0 }
        return DriveSession(
            startMillis = p.startMillis, endMillis = endMillis, distanceKm = distance,
            startOdometerKm = p.startOdometerKm, endOdometerKm = p.lastOdometerKm,
            socStart = p.socStart, socEnd = p.lastSoc, kwh = kwh,
            startPlace = p.startPlace, endPlace = p.lastPlace,
            startLat = p.startLat, startLon = p.startLon, endLat = p.lastLat, endLon = p.lastLon,
        )
    }

    companion object {
        const val MOVING_KMH = 3.0
        const val IDLE_END_MS = 10 * 60_000L

        /** Trips shorter than this (moving in a car park) are not worth a line. */
        const val MIN_KM = 0.5

        /** Usable pack of a Model 3/Y Long Range, used when the car does not say. */
        const val DEFAULT_PACK_KWH = 75.0

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

/** Turns a finished trip into a logbook line. */
fun DriveSession.toEntry(id: Long): LogEntry {
    val memo = buildList {
        add("${minutes}분")
        kmPerKwh?.let { add("전비 %.1f km/kWh".format(it)) }
        whPerKm?.let { add("%.0f Wh/km".format(it)) }
    }.joinToString(" · ")
    return LogEntry(
        id = id,
        kind = EntryKind.DRIVE,
        timeMillis = endMillis,
        costWon = 0,
        odometerKm = endOdometerKm?.toInt(),
        kwh = kwh,
        place = startPlace.orEmpty(),
        placeEnd = endPlace.orEmpty(),
        memo = "자동 기록 · $memo",
        auto = true,
        distanceKm = distanceKm,
        socStart = socStart,
        socEnd = socEnd,
        latitude = endLat,
        longitude = endLon,
        source = "차량",
    )
}
