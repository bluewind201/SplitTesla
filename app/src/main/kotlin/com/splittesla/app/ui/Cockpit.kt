package com.splittesla.app.ui

import com.splittesla.app.LinkState
import com.splittesla.app.UiState
import com.splittesla.core.camera.CameraStatus
import com.splittesla.core.drive.SpeedOrigin
import com.splittesla.core.s3xy.Latch
import com.splittesla.core.s3xy.VehicleSignal
import com.tesla.generated.vcsec.Vcsec

enum class Closure { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, FRUNK, TRUNK, CHARGE_PORT }

/**
 * Everything the dashboard draws, merged from both links. The Commander's
 * live CAN stream wins when it has a value; the car's own BLE status fills in
 * locks and doors while the Commander is away.
 */
data class Cockpit(
    val speedKmh: Float?,
    val speedSource: String,
    /** The road's limit from Tesla's own map data, read from the Commander. */
    val limitKmh: Int?,
    val gear: String?,
    val driveMode: String?,
    val regen: String?,
    val locked: Boolean?,
    /** Open closures; empty when all are known closed, null when nothing is known. */
    val open: Set<Closure>?,
    val socPercent: Int?,
    val rangeKm: Int?,
    val powerKw: Float?,
    val outsideTempC: Float?,
    val insideTempC: Float?,
    val blindLeft: Boolean,
    val blindRight: Boolean,
    val turnLeft: Boolean,
    val turnRight: Boolean,
    val brake: Boolean,
    /** Where [speedKmh] came from: Commander CAN, the car's BLE, or the phone's GPS. */
    val speedOrigin: SpeedOrigin = SpeedOrigin.NONE,
    /** Speed camera ahead, or the section enforcement zone being driven. */
    val camera: CameraStatus = CameraStatus(),
) {
    /**
     * The limit that matters now: a camera's (or section's) limit while one is
     * ahead, since that is what is enforced; else the road's limit from the car's map.
     */
    val activeLimit: Int? get() = camera.limitKmh.takeIf { camera.active } ?: limitKmh
    val overLimit: Boolean
        get() {
            val s = speedKmh ?: return false
            val l = activeLimit ?: return false
            return s > l + 0.5f
        }

    /** Short label for the speed's source; empty for CAN, the normal case. */
    val originBadge: String
        get() = when (speedOrigin) {
            SpeedOrigin.CAR -> "차량 BLE"
            SpeedOrigin.GPS -> "GPS"
            else -> ""
        }

    companion object {
        fun from(ui: UiState): Cockpit {
            // Nothing from the Commander survives its disconnect, but guard anyway.
            val d = if (ui.commander == LinkState.CONNECTED) ui.live else com.splittesla.core.s3xy.VehicleData()
            val status = ui.vehicleStatus
            val car = ui.carDrive
            val lockedLive = d.long(VehicleSignal.CAR_LOCKED)?.let { it == 1L }
            val lockedBle = status?.vehicleLockState?.let {
                it == Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED ||
                    it == Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED
            }
            return Cockpit(
                speedKmh = ui.speed.kmh,
                speedSource = ui.speed.origin.label,
                limitKmh = d.speedLimit?.toInt()?.takeIf { it > 0 },
                gear = ui.speed.gear ?: d.gear?.label,
                driveMode = driveModeLabel(d.long(VehicleSignal.ACCELERATION_MODE), d.long(VehicleSignal.TRACK_MODE_STATE)),
                regen = d.long(VehicleSignal.REGEN_LEVEL)?.let { if (it == 0L) "LOW" else "STANDARD" },
                locked = lockedLive ?: lockedBle,
                open = liveOpen(d) ?: bleOpen(status),
                socPercent = d.socPercent?.toInt() ?: ui.charge?.takeIf { it.hasBatteryLevel() }?.batteryLevel,
                rangeKm = d.rangeKm?.toInt()
                    ?: ui.charge?.takeIf { it.hasBatteryRange() }?.let { (it.batteryRange * 1.609344f).toInt() },
                powerKw = d.batteryPowerKw?.toFloat()?.let { -it } ?: car?.powerKw,
                outsideTempC = d.outsideTempC?.toFloat()
                    ?: ui.climate?.takeIf { it.hasOutsideTempCelsius() }?.outsideTempCelsius,
                insideTempC = ui.climate?.takeIf { it.hasInsideTempCelsius() }?.insideTempCelsius,
                blindLeft = d.blindSpotLeft == true,
                blindRight = d.blindSpotRight == true,
                turnLeft = d.turnSignalLeftOn == true,
                turnRight = d.turnSignalRightOn == true,
                brake = d.brakePressed == true,
                speedOrigin = ui.speed.origin,
                camera = ui.camera,
            )
        }

        /** Fake drive for the demo switch, so the gauges can be tried while parked. */
        fun demo(t: Float): Cockpit {
            val wave = (kotlin.math.sin(t * 0.35) * 0.5 + 0.5).toFloat()
            val speed = 15f + wave * 135f
            // A camera every ~30 s of demo time, counting down from 600 m.
            val phase = (t % 30f) / 30f
            val camera = if (phase < 0.6f) {
                CameraStatus(
                    ahead = com.splittesla.core.camera.SpeedCamera("demo", 0.0, 0.0, 100, "데모 카메라"),
                    distanceM = (600.0 * (1 - phase / 0.6f)).coerceAtLeast(10.0),
                    positionSource = "DEMO",
                )
            } else {
                CameraStatus(sectionLimitKmh = 110, sectionAverageKmh = 96.0 + wave * 20, sectionRemainingM = 4200.0 * (1 - phase), sectionSafeKmh = 104.0)
            }
            return Cockpit(
                speedKmh = speed, speedSource = "DEMO", limitKmh = 110,
                gear = "D", driveMode = "SPORT", regen = "STANDARD", locked = true, open = emptySet(),
                socPercent = 78, rangeKm = 412, powerKw = (kotlin.math.cos(t * 0.35) * 160).toFloat(),
                outsideTempC = 17f, insideTempC = 21f, blindLeft = speed > 120, blindRight = false,
                turnLeft = (t.toInt() % 6) == 0, turnRight = false, brake = false,
                speedOrigin = SpeedOrigin.NONE, camera = camera,
            )
        }

        /**
         * DI_accelMode as seen on the bus: 0 Chill, 1 Standard, 2 Sport/Insane.
         * Not yet confirmed on this car; an unknown value shows as its number.
         */
        private fun driveModeLabel(accel: Long?, track: Long?): String? = when {
            track != null && track >= 2L -> "TRACK"
            accel == null -> null
            accel == 0L -> "CHILL"
            accel == 1L -> "STANDARD"
            accel == 2L -> "SPORT"
            else -> "MODE $accel"
        }

        private fun liveOpen(d: com.splittesla.core.s3xy.VehicleData): Set<Closure>? {
            val pairs = listOf(
                Closure.FRONT_LEFT to VehicleSignal.DOOR_FRONT_LEFT, Closure.FRONT_RIGHT to VehicleSignal.DOOR_FRONT_RIGHT,
                Closure.REAR_LEFT to VehicleSignal.DOOR_REAR_LEFT, Closure.REAR_RIGHT to VehicleSignal.DOOR_REAR_RIGHT,
                Closure.FRUNK to VehicleSignal.FRUNK, Closure.TRUNK to VehicleSignal.TRUNK,
            ).map { (c, s) -> c to d.latch(s) }
            if (pairs.all { it.second == null }) return null
            return pairs.filter { it.second != null && it.second != Latch.CLOSED }.map { it.first }.toSet()
        }

        private fun bleOpen(status: Vcsec.VehicleStatus?): Set<Closure>? {
            if (status == null || !status.hasClosureStatuses()) return null
            val c = status.closureStatuses
            val open = setOf(
                Vcsec.ClosureState_E.CLOSURESTATE_OPEN, Vcsec.ClosureState_E.CLOSURESTATE_AJAR,
                Vcsec.ClosureState_E.CLOSURESTATE_OPENING, Vcsec.ClosureState_E.CLOSURESTATE_CLOSING,
                Vcsec.ClosureState_E.CLOSURESTATE_FAILED_UNLATCH,
            )
            return listOf(
                Closure.FRONT_LEFT to c.frontDriverDoor, Closure.FRONT_RIGHT to c.frontPassengerDoor,
                Closure.REAR_LEFT to c.rearDriverDoor, Closure.REAR_RIGHT to c.rearPassengerDoor,
                Closure.FRUNK to c.frontTrunk, Closure.TRUNK to c.rearTrunk, Closure.CHARGE_PORT to c.chargePort,
            ).filter { it.second in open }.map { it.first }.toSet()
        }
    }
}
