package com.splittesla.core.s3xy

/**
 * Signals pushed by the Commander. The firmware only sends fields that
 * changed, so a single push is a delta; [mergedOnto] builds the full state.
 *
 * Values are kept raw ([Long] for integers, [Float], [String], [Boolean]) and
 * the typed accessors below apply the scales measured in a Model 3
 * (S3XYCommander README, "Units").
 */
data class VehicleData(val values: Map<VehicleSignal, Any> = emptyMap()) {

    fun mergedOnto(base: VehicleData): VehicleData = VehicleData(base.values + values)

    operator fun get(signal: VehicleSignal): Any? = values[signal]
    fun long(signal: VehicleSignal): Long? = values[signal] as? Long
    fun float(signal: VehicleSignal): Float? = values[signal] as? Float
    fun string(signal: VehicleSignal): String? = values[signal] as? String
    fun bool(signal: VehicleSignal): Boolean? = values[signal] as? Boolean

    val isEmpty: Boolean get() = values.isEmpty()

    // Driving
    val speedKmh: Long? get() = long(VehicleSignal.SPEED)
    val accelPedalPercent: Long? get() = long(VehicleSignal.ACCEL_PEDAL_POS)
    val gear: Gear? get() = long(VehicleSignal.GEAR)?.let(Gear::of)
    val brakePressed: Boolean? get() = bool(VehicleSignal.BRAKE_PRESSED)
    val turnSignalLeftOn: Boolean? get() = long(VehicleSignal.TURN_SIGNAL_LEFT)?.let { it == 1L || it == 2L }
    val turnSignalRightOn: Boolean? get() = long(VehicleSignal.TURN_SIGNAL_RIGHT)?.let { it == 1L || it == 2L }

    // Trip and battery
    val socPercent: Long? get() = long(VehicleSignal.SOC_PERCENT)
    val rangeKm: Double? get() = long(VehicleSignal.RANGE_MILES)?.let { it * 1.609344 }
    val batteryVolts: Double? get() = long(VehicleSignal.BMS_BATTERY_VOLTAGE)?.let { it / 100.0 }
    val batteryAmps: Double? get() = long(VehicleSignal.BMS_BATTERY_CURRENT)?.let { it / 10.0 }

    /** Negative while the pack gives energy, positive while it charges. */
    val batteryPowerKw: Double?
        get() {
            val v = batteryVolts ?: return null
            val a = batteryAmps ?: return null
            return v * a / 1000.0
        }
    val maxRegenPowerKw: Double? get() = long(VehicleSignal.BMS_MAX_REGEN_POWER)?.let { it / 100.0 }
    val maxDischargePowerKw: Double? get() = long(VehicleSignal.BMS_MAX_DISCHARGE_POWER)?.let { it / 100.0 }
    val nominalFullPackNowKwh: Double? get() = long(VehicleSignal.BMS_NOMINAL_FULL_PACK_NOW)?.let { it * 0.02 }
    val nominalRemainingKwh: Double? get() = long(VehicleSignal.BMS_NOMINAL_REMAINING)?.let { it * 0.02 }
    val cellMaxVolts: Double? get() = long(VehicleSignal.BMS_CELL_MAX_VOLTAGE)?.let { it * 0.002 }
    val cellMinVolts: Double? get() = long(VehicleSignal.BMS_CELL_MIN_VOLTAGE)?.let { it * 0.002 }
    val cellMaxTempC: Long? get() = long(VehicleSignal.BMS_CELL_MAX_TEMP)
    val cellMinTempC: Long? get() = long(VehicleSignal.BMS_CELL_MIN_TEMP)
    val lowBusVolts: Double? get() = long(VehicleSignal.CHARGING_LOW_BUS_VOLTAGE)?.let { it / 100.0 }

    // Drivetrain (scale while driving not yet confirmed)
    val frontPowerKw: Long? get() = long(VehicleSignal.DRIVETRAIN_FRONT_POWER)
    val rearPowerKw: Long? get() = long(VehicleSignal.DRIVETRAIN_REAR_POWER)
    val frontTorque: Long? get() = long(VehicleSignal.DRIVETRAIN_FRONT_TORQUE)
    val rearTorque: Long? get() = long(VehicleSignal.DRIVETRAIN_REAR_TORQUE)
    val frontStatorTempC: Long? get() = long(VehicleSignal.DRIVETRAIN_TEMP_FRONT_STATOR)
    val rearStatorTempC: Long? get() = long(VehicleSignal.DRIVETRAIN_TEMP_REAR_STATOR)

    // Autopilot
    val blindSpotLeft: Boolean? get() = long(VehicleSignal.BLIND_SPOT_REAR_LEFT)?.let(::blindSpotWarning)
    val blindSpotRight: Boolean? get() = long(VehicleSignal.BLIND_SPOT_REAR_RIGHT)?.let(::blindSpotWarning)
    val speedLimit: Long? get() = long(VehicleSignal.AUTOPILOT_SPEED_LIMIT)

    // Sensors
    val outsideTempC: Long? get() = long(VehicleSignal.OUTSIDE_TEMPERATURE)

    fun latch(signal: VehicleSignal): Latch? = long(signal)?.let(Latch::of)

    private fun blindSpotWarning(v: Long): Boolean? = when (v) {
        0L -> false
        1L, 2L -> true
        else -> null
    }
}

/** Shifter position, Tesla DI_gear values (confirmed in a Model 3). */
enum class Gear(val code: Long, val label: String) {
    PARK(1, "P"), REVERSE(2, "R"), NEUTRAL(3, "N"), DRIVE(4, "D");

    companion object {
        fun of(code: Long): Gear? = entries.firstOrNull { it.code == code }
    }
}

/** LatchStatus: 1 opened, 2 closed, 3 closing, 4 opening, 5 ajar. */
enum class Latch {
    OPEN, CLOSED, AJAR;

    companion object {
        fun of(code: Long): Latch? = when (code) {
            1L, 3L, 4L -> OPEN
            2L -> CLOSED
            5L -> AJAR
            else -> null
        }
    }
}

object VehicleDataDecoder {
    /** Parses a PushVehicleDataHolder payload into a delta snapshot. */
    fun decode(data: ByteArray): VehicleData {
        val r = ProtoReader(data)
        val values = HashMap<VehicleSignal, Any>()
        while (!r.atEnd) {
            val (field, wire) = r.tag()
            val signal = VehicleSignal.ofField(field)
            if (signal == null) {
                r.skip(wire)
                continue
            }
            val value: Any? = when (signal.kind) {
                SignalKind.FLOAT -> if (wire == 5) Float.fromBits(r.fixed32()) else null
                SignalKind.STRING -> if (wire == 2) String(r.bytes(), Charsets.UTF_8) else null
                SignalKind.UINT -> if (wire == 0) r.varint() and 0xFFFFFFFFL else null
                SignalKind.SINT32 -> if (wire == 0) zigzag32(r.varint()).toLong() else null
                SignalKind.SINT64 -> if (wire == 0) zigzag64(r.varint()) else null
                SignalKind.INT32 -> if (wire == 0) r.varint().toInt().toLong() else null
                SignalKind.BOOL -> if (wire == 0) r.varint() != 0L else null
            }
            if (value == null) r.skip(wire) else values[signal] = value
        }
        return VehicleData(values)
    }
}
