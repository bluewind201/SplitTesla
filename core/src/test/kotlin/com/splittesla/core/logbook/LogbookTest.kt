package com.splittesla.core.logbook

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogbookTest {
    private fun kst(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
        clear(); set(y, m - 1, d, 12, 0)
    }.timeInMillis

    private val entries = listOf(
        LogEntry(1, EntryKind.CHARGE, kst(2026, 9, 3), 9_600, odometerKm = 10_000, kwh = 40.0, chargeType = ChargeType.HOME),
        LogEntry(2, EntryKind.CHARGE, kst(2026, 10, 2), 24_000, odometerKm = 10_500, kwh = 50.0, chargeType = ChargeType.SUPERCHARGER),
        LogEntry(3, EntryKind.TOLL, kst(2026, 10, 5), 4_400, place = "서울, 톨게이트"),
        LogEntry(4, EntryKind.MAINTENANCE, kst(2026, 10, 10), 30_000, odometerKm = 11_000, item = MaintenanceItem.TIRE_ROTATION, memo = "탭\t포함"),
    )

    @Test
    fun summarizesCostEnergyAndDistance() {
        val s = Logbook.summarize(entries)
        assertEquals(68_000, s.totalWon)
        assertEquals(90.0, s.chargeKwh)
        assertEquals(33_600.0 / 90.0, s.wonPerKwh!!, 1e-9)
        assertEquals(1_000, s.distanceKm)
        assertEquals(68.0, s.wonPerKm!!, 1e-9)

        val oct = Logbook.summarize(entries, Logbook.monthStart(kst(2026, 10, 15)), Logbook.monthStart(kst(2026, 10, 15), -1))
        assertEquals(58_400, oct.totalWon)
        assertEquals(1, oct.chargeCount)
    }

    @Test
    fun monthlyTotalsOldestFirst() {
        val months = Logbook.monthly(entries, kst(2026, 10, 20), 3)
        assertEquals(listOf(8, 9, 10), months.map { it.month })
        assertEquals(listOf(0L, 9_600L, 58_400L), months.map { it.totalWon })
        assertEquals(24_000, months[2].chargeWon)
    }

    @Test
    fun maintenanceDueByKmAndTime() {
        val due = Logbook.maintenanceDue(entries, kst(2026, 10, 20), 20_500).associateBy { it.item }
        val tires = due.getValue(MaintenanceItem.TIRE_ROTATION)
        assertEquals(21_000, tires.nextKm)
        assertEquals(500, tires.kmLeft)
        assertEquals(DueLevel.SOON, tires.level)
        assertEquals(DueLevel.NO_RECORD, due.getValue(MaintenanceItem.CABIN_FILTER).level)

        val later = Logbook.maintenanceDue(entries, kst(2026, 10, 20), 21_200).first { it.item == MaintenanceItem.TIRE_ROTATION }
        assertEquals(DueLevel.DUE, later.level)

        val wiper = listOf(LogEntry(9, EntryKind.MAINTENANCE, kst(2025, 11, 1), 20_000, item = MaintenanceItem.WIPER))
        val w = Logbook.maintenanceDue(wiper, kst(2026, 10, 20), null).first { it.item == MaintenanceItem.WIPER }
        assertEquals(DueLevel.SOON, w.level)
        assertTrue(w.daysLeft!! in 1..30)
    }

    @Test
    fun storeRoundTripsAndCsvQuotes() {
        val decoded = LogbookStore.decode(LogbookStore.encode(entries))
        assertEquals(entries.size, decoded.size)
        assertEquals("탭 포함", decoded[3].memo)
        assertEquals(entries.take(3), decoded.take(3))
        val csv = Logbook.toCsv(entries) { "d" }
        assertTrue(csv.startsWith("﻿날짜"))
        assertTrue(csv.contains("\"서울, 톨게이트\""))
    }

    @Test
    fun trackerEmitsSessionWhenChargingStops() {
        val t = ChargeSessionTracker()
        assertNull(t.feed(false, 12.0, ChargeType.HOME, 0))
        assertNull(t.feed(true, 0.2, ChargeType.SUPERCHARGER, 1_000))
        assertNull(t.feed(true, 30.5, ChargeType.HOME, 2_000))
        val s = assertNotNull(t.feed(false, 31.0, ChargeType.HOME, 3_000))
        assertEquals(1_000, s.startMillis)
        assertEquals(31.0, s.kwh)
        assertEquals(ChargeType.SUPERCHARGER, s.type)
        assertNull(t.feed(false, 31.0, ChargeType.HOME, 4_000))
        assertNull(t.current)

        t.feed(true, 0.1, ChargeType.HOME, 5_000)
        assertNull(t.feed(false, 0.3, ChargeType.HOME, 6_000))
    }
}
