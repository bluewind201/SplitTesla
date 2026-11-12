package com.splittesla.core.logbook

/** Readings the logbook watches for trouble. Any may be unknown. */
data class HealthReading(
    /** 12 V (low voltage) bus, volts. */
    val lowVolts: Double? = null,
    /** Tyre pressures in bar: front left, front right, rear left, rear right. */
    val tyreBar: List<Double?> = emptyList(),
    /** The car's own tyre warnings, same order. */
    val tyreWarnings: List<Boolean> = emptyList(),
    /** Highest minus lowest cell voltage, volts. */
    val cellSpreadVolts: Double? = null,
    val cellMaxTempC: Double? = null,
)

data class HealthIssue(val code: String, val title: String, val detail: String, val item: MaintenanceItem? = null)

/**
 * Finds problems worth a note in the logbook. Each kind of problem is
 * noted at most once per [cooldownMs], so a weak 12 V battery does not fill
 * the log.
 */
class HealthMonitor(private val cooldownMs: Long = 24 * 3_600_000L) {
    private val lastNoted = HashMap<String, Long>()

    fun restore(noted: Map<String, Long>) { lastNoted.putAll(noted) }
    fun snapshot(): Map<String, Long> = HashMap(lastNoted)

    /** Issues in this reading that have not been noted within the cool-down. */
    fun check(r: HealthReading, nowMillis: Long): List<HealthIssue> =
        issues(r).filter { i ->
            val last = lastNoted[i.code]
            if (last != null && nowMillis - last < cooldownMs) return@filter false
            lastNoted[i.code] = nowMillis
            true
        }

    companion object {
        /** Below this at rest a 12 V lead-acid or Li-ion LV battery is weak. */
        const val LOW_12V = 11.8
        const val HIGH_12V = 15.5
        /** Tesla's placard is about 2.9 bar; 0.4 under or over is worth checking. */
        const val TYRE_LOW_BAR = 2.5
        const val TYRE_HIGH_BAR = 3.4
        const val CELL_SPREAD_V = 0.05
        const val CELL_HOT_C = 55.0

        private val TYRES = listOf("앞 왼쪽", "앞 오른쪽", "뒤 왼쪽", "뒤 오른쪽")

        fun issues(r: HealthReading): List<HealthIssue> = buildList {
            r.lowVolts?.let { v ->
                if (v in 5.0..LOW_12V) add(HealthIssue("12V_LOW", "12V 저전압", "12V 전압 %.2f V (기준 %.1f V 미만). 12V 배터리 점검 필요".format(v, LOW_12V), MaintenanceItem.BATTERY_12V))
                if (v > HIGH_12V) add(HealthIssue("12V_HIGH", "12V 과전압", "12V 전압 %.2f V. 충전 계통 점검 필요".format(v), MaintenanceItem.BATTERY_12V))
            }
            r.tyreBar.forEachIndexed { i, bar ->
                val name = TYRES.getOrElse(i) { "타이어 ${i + 1}" }
                val warned = r.tyreWarnings.getOrElse(i) { false }
                when {
                    bar != null && bar > 0.3 && bar < TYRE_LOW_BAR -> add(HealthIssue("TPMS_LOW_$i", "타이어 공기압 낮음", "$name %.2f bar (권장 2.9 bar)".format(bar), MaintenanceItem.OTHER))
                    bar != null && bar > TYRE_HIGH_BAR -> add(HealthIssue("TPMS_HIGH_$i", "타이어 공기압 높음", "$name %.2f bar (권장 2.9 bar)".format(bar), MaintenanceItem.OTHER))
                    warned -> add(HealthIssue("TPMS_WARN_$i", "타이어 경고", "$name 차량 TPMS 경고", MaintenanceItem.OTHER))
                }
            }
            r.cellSpreadVolts?.let { s ->
                if (s > CELL_SPREAD_V) add(HealthIssue("CELL_SPREAD", "배터리 셀 편차 큼", "셀 전압 편차 %.0f mV".format(s * 1000)))
            }
            r.cellMaxTempC?.let { t ->
                if (t > CELL_HOT_C) add(HealthIssue("CELL_HOT", "배터리 고온", "셀 최고 온도 %.0f °C".format(t)))
            }
        }
    }
}

fun HealthIssue.toEntry(id: Long, timeMillis: Long, odometerKm: Int?): LogEntry = LogEntry(
    id = id,
    kind = EntryKind.CHECK,
    timeMillis = timeMillis,
    costWon = 0,
    odometerKm = odometerKm,
    item = item,
    memo = "자동 점검 · $title: $detail",
    auto = true,
    source = "차량",
)
