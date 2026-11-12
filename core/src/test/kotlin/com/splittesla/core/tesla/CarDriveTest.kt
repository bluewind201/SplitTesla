package com.splittesla.core.tesla

import com.tesla.generated.carserver.vehicle.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CarDriveTest {
    private val void = com.tesla.generated.carserver.common.Common.Void.getDefaultInstance()

    private fun shift(g: String): Vehicle.ShiftState = Vehicle.ShiftState.newBuilder().apply {
        when (g) {
            "P" -> setP(void)
            "R" -> setR(void)
            "N" -> setN(void)
            "D" -> setD(void)
            else -> setSNA(void)
        }
    }.build()

    @Test
    fun driveStateGivesSpeedGearPowerAndOdometer() {
        val drive = Vehicle.DriveState.newBuilder()
            .setShiftState(shift("D"))
            .setSpeedFloat(50f)
            .setPower(42)
            .setOdometerInHundredthsOfAMile(1_000_000)
            .build()
        val loc = Vehicle.LocationState.newBuilder().setLatitude(37.5f).setLongitude(127.0f).setHeading(90).build()
        val cd = CarDrive.from(drive, loc, 1_000L)
        assertEquals(80.4672f, cd.speedKmh!!, 0.01f)
        assertEquals("D", cd.gear)
        assertEquals(42f, cd.powerKw)
        assertEquals(16_093.44, cd.odometerKm!!, 0.01)
        assertEquals(37.5, cd.latitude!!, 0.001)
        assertEquals(90, cd.heading)
        assertTrue(cd.driving)
    }

    @Test
    fun wholeMphUsedWhenNoFloat() {
        val drive = Vehicle.DriveState.newBuilder().setShiftState(shift("R")).setSpeed(5).build()
        val cd = CarDrive.from(drive, null, 0L)
        assertEquals(8.04672f, cd.speedKmh!!, 0.001f)
        assertEquals("R", cd.gear)
        assertNull(cd.latitude)
    }

    @Test
    fun parkedWithoutSpeedReadsZero() {
        val drive = Vehicle.DriveState.newBuilder().setShiftState(shift("P")).build()
        val cd = CarDrive.from(drive, null, 0L)
        assertEquals(0f, cd.speedKmh)
        assertEquals("P", cd.gear)
        assertFalse(cd.driving)
    }

    @Test
    fun unknownShiftStateIsNoGear() {
        val drive = Vehicle.DriveState.newBuilder().setShiftState(shift("SNA")).build()
        val cd = CarDrive.from(drive, null, 0L)
        assertNull(cd.gear)
        assertNull(cd.speedKmh)
    }

    @Test
    fun navigationSpeedUsesSameFallback() {
        val data = Vehicle.VehicleData.newBuilder()
            .setDriveState(Vehicle.DriveState.newBuilder().setShiftState(shift("D")).setSpeed(10))
            .build()
        assertNotNull(NavigationInfo.from(data).speedKmh)
        assertEquals(16.09, NavigationInfo.from(data).speedKmh!!, 0.01)
    }
}
