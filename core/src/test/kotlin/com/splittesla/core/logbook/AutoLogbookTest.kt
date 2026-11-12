package com.splittesla.core.logbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoLogbookTest {
    @Test
    fun driveSessionFromGearAndOdometer() {
        val t = DriveSessionTracker()
        assertNull(t.feed(DriveSample(0, "P", 0.0, 1000.0, 80, place = "분당구 정자동")))
        assertNull(t.feed(DriveSample(1_000, "D", 10.0, 1000.0, 80, 37.36, 127.10, "분당구 정자동")))
        assertNull(t.feed(DriveSample(600_000, "D", 60.0, 1012.0, 78, 37.40, 127.11, "성남시 판교동")))
        val s = assertNotNull(t.feed(DriveSample(1_200_000, "P", 0.0, 1025.5, 76, 37.50, 127.03, "강남구 역삼동")))
        assertEquals(25.5, s.distanceKm, 1e-9)
        assertEquals(80, s.socStart)
        assertEquals(76, s.socEnd)
        assertEquals(3.0, s.kwh!!, 1e-9) // 4 % of 75 kWh
        assertEquals("분당구 정자동", s.startPlace)
        assertEquals("강남구 역삼동", s.endPlace)
        assertEquals(8.5, s.kmPerKwh!!, 1e-9)
        assertNull(t.current)

        val e = s.toEntry(7)
        assertEquals(EntryKind.DRIVE, e.kind)
        assertEquals(25.5, e.distanceKm)
        assertEquals(1025, e.odometerKm)
        assertEquals(0, e.costWon)
        assertEquals("강남구 역삼동", e.placeEnd)
    }

    @Test
    fun shortMovesAreDroppedAndGpsFillsMissingOdometer() {
        val t = DriveSessionTracker()
        t.feed(DriveSample(0, "D", 5.0, 500.0, 50))
        assertNull(t.feed(DriveSample(60_000, "P", 0.0, 500.1, 50)))

        t.feed(DriveSample(0, "D", 30.0, null, null, 37.0, 127.0))
        t.feed(DriveSample(60_000, "D", 30.0, null, null, 37.01, 127.0))
        val s = assertNotNull(t.feed(DriveSample(120_000, "P", 0.0, null, null, 37.02, 127.0)))
        assertTrue(s.distanceKm in 2.1..2.3)
        assertNull(s.kwh)
    }

    @Test
    fun measuredPackEnergyBeatsSoc() {
        val t = DriveSessionTracker()
        t.feed(DriveSample(0, "D", 20.0, 0.0, 80, remainingKwh = 60.0))
        val s = assertNotNull(t.feed(DriveSample(10_000, "P", 0.0, 10.0, 79, remainingKwh = 58.5)))
        assertEquals(1.5, s.kwh!!, 1e-9)
    }

    @Test
    fun chargeSessionCarriesRangeAndSoc() {
        val t = ChargeSessionTracker()
        t.feed(true, 1.0, ChargeType.SUPERCHARGER, 0, rangeAddedKm = 5.0, socPercent = 30)
        t.feed(true, 30.0, ChargeType.SUPERCHARGER, 1_000, rangeAddedKm = 190.0, socPercent = 70)
        val s = assertNotNull(t.feed(false, 30.0, ChargeType.HOME, 2_000, socPercent = 71))
        assertEquals(190.0, s.rangeAddedKm)
        assertEquals(30, s.socStart)
        assertEquals(71, s.socEnd)

        // No range from the car: estimated from the battery levels.
        val u = ChargeSessionTracker()
        u.feed(true, 10.0, ChargeType.HOME, 0, socPercent = 50)
        val e = assertNotNull(u.feed(false, 10.0, ChargeType.HOME, 1, socPercent = 60, ratedKmPerPercent = 5.0))
        assertEquals(50.0, e.rangeAddedKm)

        val restored = ChargeSessionTracker.decodePending(ChargeSessionTracker(ChargeSessionTracker.Pending(1, 2.0, ChargeType.FAST, 10.0, 20, 25)).encodePending())
        assertEquals(ChargeSessionTracker.Pending(1, 2.0, ChargeType.FAST, 10.0, 20, 25), restored)
    }

    @Test
    fun wonPerChargedKmInSummaryAndEntry() {
        val entries = listOf(
            LogEntry(1, EntryKind.CHARGE, 1, 20_000, kwh = 40.0, chargedRangeKm = 250.0),
            LogEntry(2, EntryKind.CHARGE, 2, 10_000, kwh = 20.0, chargedRangeKm = 150.0),
            LogEntry(3, EntryKind.CHARGE, 3, 5_000, kwh = 10.0),
            LogEntry(4, EntryKind.DRIVE, 4, 0, kwh = 6.0, distanceKm = 42.0),
        )
        val s = Logbook.summarize(entries)
        assertEquals(400.0, s.chargedRangeKm)
        assertEquals(75.0, s.wonPerChargedKm!!, 1e-9)
        assertEquals(7_500.0, s.wonPer100ChargedKm!!, 1e-9)
        assertEquals(6.666, s.chargedKmPerKwh!!, 1e-3)
        assertEquals(1, s.driveCount)
        assertEquals(7.0, s.driveKmPerKwh!!, 1e-9)
        assertEquals(80.0, entries[0].wonPerChargedKm!!, 1e-9)
        assertEquals(8_000.0, entries[0].wonPer100ChargedKm!!, 1e-9)
        assertEquals(500.0, entries[0].wonPerKwhActual!!, 1e-9)
        assertEquals(35_000, s.totalWon)
    }

    @Test
    fun storeKeepsNewColumnsAndReadsOldLines() {
        val e = LogEntry(
            9, EntryKind.DRIVE, 100, 0, odometerKm = 10, kwh = 2.5, place = "정자동", placeEnd = "역삼동",
            distanceKm = 12.3, socStart = 80, socEnd = 77, latitude = 37.1, longitude = 127.2, source = "차량", paid = false, auto = true,
        )
        val c = LogEntry(10, EntryKind.CHARGE, 200, 9_000, kwh = 20.0, chargedRangeKm = 130.0, paid = true, source = "문자")
        assertEquals(listOf(e, c), LogbookStore.decode(LogbookStore.encode(listOf(e, c))))
        val old = "1\tTOLL\t5\t4400\t\t\t\t\t서울\t메모\t0\n"
        val d = LogbookStore.decode(old).single()
        assertEquals(EntryKind.TOLL, d.kind)
        assertNull(d.chargedRangeKm)
        assertEquals("", d.source)
    }

    @Test
    fun healthNotesLow12vOncePerDay() {
        val m = HealthMonitor()
        val r = HealthReading(lowVolts = 11.4, tyreBar = listOf(2.9, 2.2, 2.9, 2.9))
        val first = m.check(r, 0)
        assertEquals(setOf("12V_LOW", "TPMS_LOW_1"), first.map { it.code }.toSet())
        assertTrue(m.check(r, 3_600_000).isEmpty())
        assertEquals(2, m.check(r, 25 * 3_600_000L).size)
        assertTrue(HealthMonitor.issues(HealthReading(lowVolts = 12.6, tyreBar = listOf(2.9, 2.9, 2.9, 2.9))).isEmpty())
        val entry = first.first { it.code == "12V_LOW" }.toEntry(1, 0, 100)
        assertEquals(EntryKind.CHECK, entry.kind)
        assertEquals(MaintenanceItem.BATTERY_12V, entry.item)
    }
}
