package com.splittesla.core.logbook

import java.util.Calendar
import java.util.TimeZone

/** What a logbook line is for. */
enum class EntryKind(val label: String) {
    CHARGE("충전"),
    MAINTENANCE("정비"),
    TOLL("통행료"),
    PARKING("주차"),
    WASH("세차"),
    INSURANCE("보험·세금"),
    OTHER("기타"),
    /** A trip the app recorded from the car's data; costs nothing by itself. */
    DRIVE("주행"),
    /** A problem the car's data showed (12V, tyres…), noted for the next service. */
    CHECK("점검 메모"),
}

/** Where the car charged; picks the default price per kWh. */
enum class ChargeType(val label: String, val defaultWonPerKwh: Int) {
    HOME("집밥", 200),
    SUPERCHARGER("슈퍼차저", 480),
    FAST("공용 급속", 347),
    SLOW("공용 완속", 280),
}

/**
 * Service items with Tesla's suggested interval for a Model 3/Y
 * (owner's manual "Maintenance intervals"). Items without an interval are
 * logged but never reminded.
 */
enum class MaintenanceItem(val label: String, val intervalKm: Int?, val intervalMonths: Int?) {
    TIRE_ROTATION("타이어 위치 교환", 10_000, null),
    CABIN_FILTER("에어컨 필터", null, 24),
    HEPA_FILTER("HEPA 필터", null, 36),
    BRAKE_FLUID("브레이크액 점검", null, 48),
    BRAKE_CALIPER("브레이크 캘리퍼 청소·윤활", 20_000, 12),
    WIPER("와이퍼", null, 12),
    AC_DESICCANT("에어컨 건조제", null, 72),
    TIRE_REPLACE("타이어 교체", null, null),
    WASHER_FLUID("워셔액", null, null),
    BATTERY_12V("12V 배터리", null, null),
    OTHER("기타 정비", null, null),
}

data class LogEntry(
    val id: Long,
    val kind: EntryKind,
    val timeMillis: Long,
    val costWon: Long,
    val odometerKm: Int? = null,
    val kwh: Double? = null,
    val chargeType: ChargeType? = null,
    val item: MaintenanceItem? = null,
    val place: String = "",
    val memo: String = "",
    /** Recorded by the app from the car's charge state, not typed in. */
    val auto: Boolean = false,
    /** Range the charge added, km (the car's rated figure). */
    val chargedRangeKm: Double? = null,
    /** Trip length for a drive, km. */
    val distanceKm: Double? = null,
    val socStart: Int? = null,
    val socEnd: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Where a drive ended; [place] is where it (or a charge) happened or began. */
    val placeEnd: String = "",
    /** Where the line came from: "차량", "문자", "카카오톡", "알림", or empty when typed in. */
    val source: String = "",
    /** The cost was confirmed from a payment message, not estimated from the unit price. */
    val paid: Boolean = false,
) {
    /** Won per km of range charged. */
    val wonPerChargedKm: Double? get() = chargedRangeKm?.takeIf { it > 0 && costWon > 0 }?.let { costWon / it }

    /** Won for 100 km of range charged. */
    val wonPer100ChargedKm: Double? get() = wonPerChargedKm?.let { it * 100 }

    val wonPerKwhActual: Double? get() = kwh?.takeIf { it > 0 && costWon > 0 }?.let { costWon / it }

    /** km of range per kWh charged, or km driven per kWh used on a drive. */
    val kmPerKwh: Double?
        get() {
            val k = kwh?.takeIf { it > 0 } ?: return null
            return when (kind) {
                EntryKind.DRIVE -> distanceKm?.let { it / k }
                EntryKind.CHARGE -> chargedRangeKm?.let { it / k }
                else -> null
            }
        }

    val title: String
        get() = when (kind) {
            EntryKind.CHARGE -> chargeType?.label ?: kind.label
            EntryKind.MAINTENANCE -> item?.label ?: kind.label
            else -> kind.label
        }
}

data class Summary(
    val totalWon: Long,
    val byKind: Map<EntryKind, Long>,
    val chargeKwh: Double,
    val chargeWon: Long,
    val chargeCount: Int,
    /** Odometer span of the entries in range, or null with fewer than two readings. */
    val distanceKm: Int?,
    /** Range added by the charges that report it, and what those charges cost. */
    val chargedRangeKm: Double = 0.0,
    val chargedRangeWon: Long = 0,
    val chargedRangeKwh: Double = 0.0,
    /** Recorded drives. */
    val driveCount: Int = 0,
    val driveKm: Double = 0.0,
    val driveKwh: Double = 0.0,
) {
    /** Won per km of range charged. */
    val wonPerChargedKm: Double? get() = if (chargedRangeKm > 0 && chargedRangeWon > 0) chargedRangeWon / chargedRangeKm else null
    val wonPer100ChargedKm: Double? get() = wonPerChargedKm?.let { it * 100 }

    /** km of range per kWh charged. */
    val chargedKmPerKwh: Double? get() = if (chargedRangeKm > 0 && chargedRangeKwh > 0) chargedRangeKm / chargedRangeKwh else null

    /** Driving efficiency from recorded drives. */
    val driveKmPerKwh: Double? get() = if (driveKm > 0 && driveKwh > 0) driveKm / driveKwh else null

    val wonPerKwh: Double? get() = if (chargeKwh > 0.0) chargeWon / chargeKwh else null
    val wonPerKm: Double? get() = distanceKm?.takeIf { it > 0 }?.let { totalWon.toDouble() / it }
    /** Energy charged per 100 km driven, a rough consumption figure. */
    val kwhPer100Km: Double? get() = distanceKm?.takeIf { it > 0 && chargeKwh > 0 }?.let { chargeKwh * 100 / it }
}

data class MonthTotal(val year: Int, val month: Int, val totalWon: Long, val chargeWon: Long)

enum class DueLevel { OK, SOON, DUE, NO_RECORD }

data class MaintenanceDue(
    val item: MaintenanceItem,
    val last: LogEntry?,
    val nextKm: Int?,
    val nextMillis: Long?,
    val kmLeft: Int?,
    val daysLeft: Int?,
    val level: DueLevel,
)

object Logbook {
    private const val DAY_MS = 86_400_000L
    private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    fun summarize(entries: List<LogEntry>, fromMillis: Long = Long.MIN_VALUE, toMillis: Long = Long.MAX_VALUE): Summary {
        val inRange = entries.filter { it.timeMillis in fromMillis until toMillis }
        val charges = inRange.filter { it.kind == EntryKind.CHARGE }
        val ranged = charges.filter { (it.chargedRangeKm ?: 0.0) > 0 && it.costWon > 0 }
        val drives = inRange.filter { it.kind == EntryKind.DRIVE }
        val odo = inRange.mapNotNull { it.odometerKm }
        return Summary(
            totalWon = inRange.sumOf { it.costWon },
            byKind = inRange.groupBy { it.kind }.mapValues { (_, v) -> v.sumOf { it.costWon } },
            chargeKwh = charges.sumOf { it.kwh ?: 0.0 },
            chargeWon = charges.filter { (it.kwh ?: 0.0) > 0 }.sumOf { it.costWon },
            chargeCount = charges.size,
            distanceKm = if (odo.size >= 2) odo.max() - odo.min() else null,
            chargedRangeKm = ranged.sumOf { it.chargedRangeKm ?: 0.0 },
            chargedRangeWon = ranged.sumOf { it.costWon },
            chargedRangeKwh = ranged.sumOf { it.kwh ?: 0.0 },
            driveCount = drives.size,
            driveKm = drives.sumOf { it.distanceKm ?: 0.0 },
            driveKwh = drives.filter { (it.distanceKm ?: 0.0) > 0 }.sumOf { it.kwh ?: 0.0 },
        )
    }

    /** Start of the month containing [millis], Korean time. */
    fun monthStart(millis: Long, monthsBack: Int = 0): Long = cal(millis).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, -monthsBack)
    }.timeInMillis

    /** The last [count] months up to and including the one containing [nowMillis], oldest first. */
    fun monthly(entries: List<LogEntry>, nowMillis: Long, count: Int = 6): List<MonthTotal> =
        (count - 1 downTo 0).map { back ->
            val from = monthStart(nowMillis, back)
            val to = monthStart(nowMillis, back - 1)
            val c = cal(from)
            val s = summarize(entries, from, to)
            MonthTotal(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, s.totalWon, s.byKind[EntryKind.CHARGE] ?: 0)
        }

    /**
     * When each interval item is next due, from its last service and the
     * current odometer. SOON means within 1,000 km or 30 days.
     */
    fun maintenanceDue(entries: List<LogEntry>, nowMillis: Long, odometerKm: Int?): List<MaintenanceDue> =
        MaintenanceItem.entries.filter { it.intervalKm != null || it.intervalMonths != null }.map { item ->
            val last = entries.filter { it.kind == EntryKind.MAINTENANCE && it.item == item }.maxByOrNull { it.timeMillis }
            if (last == null) return@map MaintenanceDue(item, null, null, null, null, null, DueLevel.NO_RECORD)
            val nextKm = item.intervalKm?.let { iv -> last.odometerKm?.plus(iv) }
            val nextMillis = item.intervalMonths?.let { m -> cal(last.timeMillis).apply { add(Calendar.MONTH, m) }.timeInMillis }
            val kmLeft = if (nextKm != null && odometerKm != null) nextKm - odometerKm else null
            val daysLeft = nextMillis?.let { Math.floorDiv(it - nowMillis, DAY_MS).toInt() }
            val level = when {
                (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> DueLevel.DUE
                (kmLeft != null && kmLeft <= 1_000) || (daysLeft != null && daysLeft <= 30) -> DueLevel.SOON
                else -> DueLevel.OK
            }
            MaintenanceDue(item, last, nextKm, nextMillis, kmLeft, daysLeft, level)
        }

    /** Spreadsheet export; UTF-8 with BOM so Excel shows Korean correctly. */
    fun toCsv(entries: List<LogEntry>, formatTime: (Long) -> String): String = buildString {
        append('﻿')
        append("날짜,구분,항목,금액(원),주행거리(km),충전량(kWh),충전거리(km),충전 원/km,운행거리(km),배터리(%),장소,도착지,출처,메모,자동기록\n")
        entries.sortedBy { it.timeMillis }.forEach { e ->
            append(
                listOf(
                    formatTime(e.timeMillis), e.kind.label, e.title, e.costWon.toString(),
                    e.odometerKm?.toString().orEmpty(), e.kwh?.let { "%.2f".format(it) }.orEmpty(),
                    e.chargedRangeKm?.let { "%.0f".format(it) }.orEmpty(), e.wonPerChargedKm?.let { "%.1f".format(it) }.orEmpty(),
                    e.distanceKm?.let { "%.1f".format(it) }.orEmpty(),
                    if (e.socStart != null || e.socEnd != null) "${e.socStart ?: ""}→${e.socEnd ?: ""}" else "",
                    e.place, e.placeEnd, e.source, e.memo, if (e.auto) "Y" else "",
                ).joinToString(",") { csvField(it) },
            )
            append('\n')
        }
    }

    private fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s

    private fun cal(millis: Long) = Calendar.getInstance(KST).apply { timeInMillis = millis }
}

/** Tab-separated storage, one entry per line; free text has tabs and newlines flattened. */
object LogbookStore {
    fun encode(entries: List<LogEntry>): String = buildString {
        entries.forEach { e ->
            append(
                listOf(
                    e.id, e.kind.name, e.timeMillis, e.costWon, e.odometerKm ?: "", e.kwh ?: "",
                    e.chargeType?.name.orEmpty(), e.item?.name.orEmpty(), clean(e.place), clean(e.memo), if (e.auto) "1" else "0",
                    e.chargedRangeKm ?: "", e.distanceKm ?: "", e.socStart ?: "", e.socEnd ?: "",
                    e.latitude ?: "", e.longitude ?: "", clean(e.placeEnd), clean(e.source), if (e.paid) "1" else "0",
                ).joinToString("\t"),
            )
            append('\n')
        }
    }

    fun decode(text: String): List<LogEntry> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 11) return@mapNotNull null
        runCatching {
            LogEntry(
                id = f[0].toLong(),
                kind = EntryKind.valueOf(f[1]),
                timeMillis = f[2].toLong(),
                costWon = f[3].toLong(),
                odometerKm = f[4].toIntOrNull(),
                kwh = f[5].toDoubleOrNull(),
                chargeType = f[6].takeIf { it.isNotEmpty() }?.let { n -> ChargeType.entries.firstOrNull { it.name == n } },
                item = f[7].takeIf { it.isNotEmpty() }?.let { n -> MaintenanceItem.entries.firstOrNull { it.name == n } },
                place = f[8],
                memo = f[9],
                auto = f[10] == "1",
                // Columns added later; older files simply stop after the 11th.
                chargedRangeKm = f.getOrNull(11)?.toDoubleOrNull(),
                distanceKm = f.getOrNull(12)?.toDoubleOrNull(),
                socStart = f.getOrNull(13)?.toIntOrNull(),
                socEnd = f.getOrNull(14)?.toIntOrNull(),
                latitude = f.getOrNull(15)?.toDoubleOrNull(),
                longitude = f.getOrNull(16)?.toDoubleOrNull(),
                placeEnd = f.getOrNull(17).orEmpty(),
                source = f.getOrNull(18).orEmpty(),
                paid = f.getOrNull(19) == "1",
            )
        }.getOrNull()
    }.toList()

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/** A charge the car finished, as seen through its charge state. */
data class ChargeSession(
    val startMillis: Long,
    val endMillis: Long,
    val kwh: Double,
    val type: ChargeType,
    /** Rated range the charge added, km. */
    val rangeAddedKm: Double? = null,
    val socStart: Int? = null,
    val socEnd: Int? = null,
)

/**
 * Turns periodic charge-state readings into finished sessions. The car keeps
 * `charge_energy_added` after a charge ends and resets it when the next one
 * starts, so a session ends on the first reading that is no longer charging.
 */
class ChargeSessionTracker(private var pending: Pending? = null) {
    data class Pending(
        val startMillis: Long,
        val kwh: Double,
        val type: ChargeType,
        val rangeAddedKm: Double? = null,
        val socStart: Int? = null,
        val socNow: Int? = null,
    )

    val current: Pending? get() = pending

    /**
     * Returns a session when this reading shows the charge just ended.
     * [rangeAddedKm] is the car's `charge_miles_added_rated` in km; when the
     * car does not report it, the range is estimated from the battery levels
     * and [ratedKmPerPercent].
     */
    fun feed(
        charging: Boolean,
        energyAddedKwh: Double?,
        type: ChargeType,
        nowMillis: Long,
        rangeAddedKm: Double? = null,
        socPercent: Int? = null,
        ratedKmPerPercent: Double? = null,
    ): ChargeSession? {
        val p = pending
        if (charging) {
            pending = Pending(
                startMillis = p?.startMillis ?: nowMillis,
                kwh = maxOf(energyAddedKwh ?: 0.0, p?.kwh ?: 0.0),
                type = if (p != null && type == ChargeType.HOME) p.type else type,
                rangeAddedKm = maxOfNullable(rangeAddedKm, p?.rangeAddedKm),
                socStart = p?.socStart ?: socPercent,
                socNow = socPercent ?: p?.socNow,
            )
            return null
        }
        if (p == null) return null
        pending = null
        val kwh = maxOf(energyAddedKwh ?: 0.0, p.kwh)
        if (kwh < MIN_KWH) return null
        val socEnd = socPercent ?: p.socNow
        val range = maxOfNullable(rangeAddedKm, p.rangeAddedKm)
            ?: if (p.socStart != null && socEnd != null && ratedKmPerPercent != null && socEnd > p.socStart) (socEnd - p.socStart) * ratedKmPerPercent else null
        return ChargeSession(p.startMillis, nowMillis, kwh, p.type, range?.takeIf { it > 0 }, p.socStart, socEnd)
    }

    private fun maxOfNullable(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /** One line for app storage, so a charge in progress survives the app being closed. */
    fun encodePending(): String? = pending?.let { p ->
        listOf(p.startMillis, p.kwh, p.type.name, p.rangeAddedKm ?: "", p.socStart ?: "", p.socNow ?: "").joinToString("|")
    }

    companion object {
        fun decodePending(s: String?): Pending? {
            val f = s?.split('|') ?: return null
            if (f.size < 3) return null
            return runCatching {
                Pending(
                    f[0].toLong(), f[1].toDouble(), ChargeType.valueOf(f[2]),
                    f.getOrNull(3)?.toDoubleOrNull(), f.getOrNull(4)?.toIntOrNull(), f.getOrNull(5)?.toIntOrNull(),
                )
            }.getOrNull()
        }

        /** Ignore top-ups too small to be worth a line. */
        const val MIN_KWH = 0.5
    }
}

