package com.splittesla.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeedSourceTest {
    @Test
    fun canWinsOnlyWhileCommanderConnected() {
        val r = SpeedSource.resolve(10_000, true, 80f, "D", 9_500, 70f, "D", 9_000, 75f, 9_900)
        assertEquals(SpeedOrigin.CAN, r.origin)
        assertEquals(80f, r.kmh)

        // Commander gone: its old CAN speed is ignored, the car's BLE speed is used.
        val car = SpeedSource.resolve(10_000, false, 80f, "D", 9_500, 70f, "R", 9_000, 75f, 9_900)
        assertEquals(SpeedOrigin.CAR, car.origin)
        assertEquals(70f, car.kmh)
        assertEquals("R", car.gear)
    }

    @Test
    fun staleValuesFallThroughToGpsThenNothing() {
        val gps = SpeedSource.resolve(20_000, false, null, null, null, 70f, "D", 1_000, 42f, 19_000)
        assertEquals(SpeedOrigin.GPS, gps.origin)
        assertEquals(42f, gps.kmh)
        assertNull(gps.gear)
        val none = SpeedSource.resolve(60_000, false, null, null, null, 70f, "D", 1_000, 42f, 19_000)
        assertEquals(SpeedOrigin.NONE, none.origin)
        assertNull(none.kmh)
    }

    @Test
    fun parkedCarShowsZeroInsteadOfGpsDrift() {
        val r = SpeedSource.resolve(10_000, false, null, null, null, null, "P", 9_000, 3f, 9_900)
        assertEquals(0f, r.kmh)
        assertEquals("P", r.gear)
    }

    @Test
    fun convertsCarUnits() {
        assertEquals(1.609344f * 50f, SpeedSource.carSpeedKmh(50f, null)!!, 1e-4f)
        assertEquals(1.609344f * 30f, SpeedSource.carSpeedKmh(null, 30)!!, 1e-4f)
        assertNull(SpeedSource.carSpeedKmh(null, null))
        assertEquals("D", SpeedSource.gearOf("D"))
        assertNull(SpeedSource.gearOf("SNA"))
    }
}
