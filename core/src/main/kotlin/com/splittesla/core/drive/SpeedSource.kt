package com.splittesla.core.drive

/** Where the speed on the gauge came from. */
enum class SpeedOrigin(val label: String) {
    /** The Commander's live CAN stream. */
    CAN("CAN"),
    /** The car's own BLE drive state (speed_float / speed). */
    CAR("BLE"),
    /** The phone's GPS. */
    GPS("GPS"),
    NONE(""),
}

data class SpeedReading(val kmh: Float?, val origin: SpeedOrigin, val gear: String?)

/**
 * Picks the speed and gear to draw. The Commander's CAN data wins only
 * while it is connected and fresh; otherwise the car's BLE drive state, then
 * the phone's GPS. A stale value is never drawn, so a dropped link cannot
 * leave the gauge frozen at the last speed.
 */
object SpeedSource {
    const val CAN_FRESH_MS = 3_000L
    const val CAR_FRESH_MS = 6_000L
    const val GPS_FRESH_MS = 4_000L

    fun resolve(
        nowMillis: Long,
        commanderConnected: Boolean,
        canKmh: Float?, canGear: String?, canAtMillis: Long?,
        carKmh: Float?, carGear: String?, carAtMillis: Long?,
        gpsKmh: Float?, gpsAtMillis: Long?,
    ): SpeedReading {
        fun fresh(at: Long?, window: Long) = at != null && nowMillis - at in 0..window
        val canOk = commanderConnected && canKmh != null && (canAtMillis == null || fresh(canAtMillis, CAN_FRESH_MS))
        val carOk = carKmh != null && fresh(carAtMillis, CAR_FRESH_MS)
        val carGearOk = carGear != null && fresh(carAtMillis, CAR_FRESH_MS)
        val gpsOk = gpsKmh != null && fresh(gpsAtMillis, GPS_FRESH_MS)
        val gear = (if (commanderConnected) canGear else null) ?: carGear.takeIf { carGearOk }
        return when {
            canOk -> SpeedReading(canKmh, SpeedOrigin.CAN, gear)
            carOk -> SpeedReading(carKmh, SpeedOrigin.CAR, gear)
            // Parked per the car: show 0 rather than GPS drift.
            gear == "P" -> SpeedReading(0f, SpeedOrigin.CAR, gear)
            gpsOk -> SpeedReading(gpsKmh, SpeedOrigin.GPS, gear)
            else -> SpeedReading(null, SpeedOrigin.NONE, gear)
        }
    }

    /** The car's ShiftState oneof name ("P", "R", "N", "D", "INVALID", "SNA") as a gear letter. */
    fun gearOf(shiftStateName: String?): String? = when (shiftStateName?.uppercase()) {
        "P" -> "P"
        "R" -> "R"
        "N" -> "N"
        "D" -> "D"
        else -> null
    }

    const val KMH_PER_MPH = 1.609344f

    /** speed_float is in mph with decimals; speed is whole mph. */
    fun carSpeedKmh(speedFloatMph: Float?, speedMph: Int?): Float? =
        (speedFloatMph ?: speedMph?.toFloat())?.let { it * KMH_PER_MPH }?.takeIf { it >= 0f && it < 400f }
}
