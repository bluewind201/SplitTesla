package com.splittesla.app.logbook

import android.content.Context
import android.net.Uri
import com.splittesla.core.logbook.ChargeSessionTracker
import com.splittesla.core.logbook.ChargeType
import com.splittesla.core.logbook.DriveSample
import com.splittesla.core.logbook.DriveSession
import com.splittesla.core.logbook.DriveSessionTracker
import com.splittesla.core.logbook.EntryKind
import com.splittesla.core.logbook.HealthMonitor
import com.splittesla.core.logbook.HealthReading
import com.splittesla.core.logbook.LogEntry
import com.splittesla.core.logbook.Logbook
import com.splittesla.core.logbook.LogbookStore
import com.splittesla.core.logbook.toEntry
import com.tesla.generated.carserver.vehicle.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogbookState(
    val entries: List<LogEntry> = emptyList(),
    /** Latest odometer the car reported over BLE, or the highest one typed in. */
    val odometerKm: Int? = null,
    val odometerFromCar: Boolean = false,
    val prices: Map<ChargeType, Int> = ChargeType.entries.associateWith { it.defaultWonPerKwh },
    /** A charge the car is doing right now, which becomes an entry when it ends. */
    val charging: ChargeSessionTracker.Pending? = null,
    /** A drive in progress, which becomes an entry once the car is parked. */
    val driving: DriveSessionTracker.Pending? = null,
    /** Record drives from the car's data. */
    val autoDrives: Boolean = true,
    /** Note 12 V, tyre and battery problems from the car's data. */
    val autoChecks: Boolean = true,
)

/** Where the car is, for a charge or drive line. */
data class PlaceInfo(val name: String?, val latitude: Double?, val longitude: Double?)

/**
 * The 차계부: costs, charges, drives and services, kept in app storage.
 * Charges record themselves from the car's charge state with where they
 * happened and how much range they added. Drives record themselves from
 * gear, odometer and battery.
 */
class LogbookController private constructor(private val context: Context) {
    @Volatile
    var log: (String) -> Unit = {}

    /** Where the car is now; set by the view model. */
    @Volatile
    var placeProvider: () -> PlaceInfo = { PlaceInfo(null, null, null) }

    private val file get() = File(context.filesDir, "logbook.tsv")
    private val prefs = context.getSharedPreferences("splittesla", Context.MODE_PRIVATE)
    private val tracker = ChargeSessionTracker(
        ChargeSessionTracker.decodePending(prefs.getString("logbook_charge_pending", null)) ?: legacyPending(),
    )
    private val drives = DriveSessionTracker()
    private val health = HealthMonitor().apply {
        restore(prefs.getStringSet("logbook_health_noted", emptySet()).orEmpty().mapNotNull { s ->
            s.substringBefore('=').takeIf { it.isNotEmpty() }?.let { k -> s.substringAfter('=').toLongOrNull()?.let { k to it } }
        }.toMap())
    }
    private val lock = Any()

    private val _state = MutableStateFlow(
        LogbookState(
            entries = load(),
            odometerKm = prefs.getInt("logbook_odometer", -1).takeIf { it >= 0 },
            prices = ChargeType.entries.associateWith { prefs.getInt("logbook_price_${it.name}", it.defaultWonPerKwh) },
            charging = tracker.current,
            autoDrives = prefs.getBoolean("logbook_auto_drives", true),
            autoChecks = prefs.getBoolean("logbook_auto_checks", true),
        ),
    )
    val state: StateFlow<LogbookState> = _state.asStateFlow()

    private var lastId = 0L

    /** Unique, increasing ids even for several lines in the same millisecond. */
    private fun newId(): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        lastId = maxOf(now, lastId + 1, (_state.value.entries.maxOfOrNull { it.id } ?: 0) + 1)
        lastId
    }

    fun save(entry: LogEntry) {
        synchronized(lock) {
            val id = if (entry.id == 0L) newId() else entry.id
            val next = (_state.value.entries.filter { it.id != id } + entry.copy(id = id)).sortedByDescending { it.timeMillis }
            persist(next)
        }
        entry.odometerKm?.let { km -> if (km > (_state.value.odometerKm ?: 0) && !_state.value.odometerFromCar) setOdometer(km, false) }
    }

    fun delete(id: Long) = synchronized(lock) { persist(_state.value.entries.filter { it.id != id }) }

    fun setPrice(type: ChargeType, wonPerKwh: Int) {
        prefs.edit().putInt("logbook_price_${type.name}", wonPerKwh).apply()
        _state.update { it.copy(prices = it.prices + (type to wonPerKwh)) }
    }

    fun onOdometer(km: Double) = setOdometer(km.toInt(), true)

    private fun setOdometer(km: Int, fromCar: Boolean) {
        prefs.edit().putInt("logbook_odometer", km).apply()
        _state.update { it.copy(odometerKm = km, odometerFromCar = fromCar || it.odometerFromCar) }
    }

    // ── Settings ──

    fun setAutoDrives(on: Boolean) {
        prefs.edit().putBoolean("logbook_auto_drives", on).apply()
        _state.update { it.copy(autoDrives = on) }
    }

    fun setAutoChecks(on: Boolean) {
        prefs.edit().putBoolean("logbook_auto_checks", on).apply()
        _state.update { it.copy(autoChecks = on) }
    }

    // ── Charges ──

    /** Feed every charge-state reading from the car here. */
    fun onChargeState(charge: Vehicle.ChargeState) {
        val name = charge.chargingState.typeCase.name
        val charging = name == "CHARGING" || name == "STARTING"
        val type = when {
            !charge.hasFastChargerPresent() || !charge.fastChargerPresent -> ChargeType.HOME
            charge.fastChargerType.typeCase.name == "SUPERCHARGER" || charge.fastChargerType.typeCase.name == "TESLA" ||
                charge.fastChargerBrand.typeCase.name == "TESLA" -> ChargeType.SUPERCHARGER
            else -> ChargeType.FAST
        }
        val kwh = charge.chargeEnergyAdded.toDouble().takeIf { charge.hasChargeEnergyAdded() }
        val rangeKm = (charge.chargeMilesAddedRated * KM_PER_MILE).takeIf { charge.hasChargeMilesAddedRated() && charge.chargeMilesAddedRated > 0 }
        val soc = charge.batteryLevel.takeIf { charge.hasBatteryLevel() }
        val kmPerPercent = if (charge.hasBatteryRange() && soc != null && soc > 0) charge.batteryRange * KM_PER_MILE / soc else null
        if (charging) endDrive()
        val session = tracker.feed(charging, kwh, type, System.currentTimeMillis(), rangeKm, soc, kmPerPercent)
        prefs.edit().putString("logbook_charge_pending", tracker.encodePending()).apply()
        _state.update { it.copy(charging = tracker.current) }
        if (session != null) {
            val price = _state.value.prices[session.type] ?: session.type.defaultWonPerKwh
            val where = placeProvider()
            val range = session.rangeAddedKm
            val memo = buildList {
                add("자동 기록 · 예상 단가 ${price}원/kWh")
                if (session.socStart != null && session.socEnd != null) add("배터리 ${session.socStart}→${session.socEnd}%")
                range?.let { add("+%.0f km".format(it)) }
            }.joinToString(" · ")
            save(
                LogEntry(
                    id = 0,
                    kind = EntryKind.CHARGE,
                    timeMillis = session.endMillis,
                    costWon = Math.round(session.kwh * price),
                    odometerKm = _state.value.odometerKm,
                    kwh = session.kwh,
                    chargeType = session.type,
                    place = where.name.orEmpty(),
                    memo = memo,
                    auto = true,
                    chargedRangeKm = range,
                    socStart = session.socStart,
                    socEnd = session.socEnd,
                    latitude = where.latitude,
                    longitude = where.longitude,
                    source = "차량",
                ),
            )
            log("차계부: 충전 ${"%.1f".format(session.kwh)} kWh${range?.let { " (+%.0f km)".format(it) }.orEmpty()} 자동 기록")
        }
    }

    // ── Drives ──

    /** Feed a reading of the car about once a second while it may be driving. */
    fun onDriveSample(sample: DriveSample) {
        if (!_state.value.autoDrives) return
        val session = drives.feed(sample)
        _state.update { it.copy(driving = drives.current) }
        session?.let(::saveDrive)
    }

    /** A charge started, or the app is closing: the drive is over. */
    fun endDrive() {
        drives.end(System.currentTimeMillis())?.let(::saveDrive)
        _state.update { it.copy(driving = null) }
    }

    private fun saveDrive(s: DriveSession) {
        save(s.toEntry(0))
        log("차계부: 주행 %.1f km 자동 기록 (%s → %s)".format(s.distanceKm, s.startPlace ?: "?", s.endPlace ?: "?"))
    }

    // ── Checks ──

    /** Notes 12 V, tyre and battery problems, each at most once a day. */
    fun onHealth(reading: HealthReading) {
        if (!_state.value.autoChecks) return
        val now = System.currentTimeMillis()
        val issues = health.check(reading, now)
        if (issues.isEmpty()) return
        prefs.edit().putStringSet("logbook_health_noted", health.snapshot().map { (k, v) -> "$k=$v" }.toSet()).apply()
        issues.forEach { i ->
            save(i.toEntry(0, now, _state.value.odometerKm))
            log("차계부: 점검 메모 — ${i.title}: ${i.detail}")
        }
    }

    suspend fun exportCsv(uri: Uri) = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        val csv = Logbook.toCsv(_state.value.entries) { fmt.format(Date(it)) }
        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("파일을 쓸 수 없습니다")
    }

    private fun persist(entries: List<LogEntry>) {
        _state.update { it.copy(entries = entries) }
        // A few hundred lines at most; writing on the caller's thread keeps saves ordered.
        file.writeText(LogbookStore.encode(entries))
    }

    private fun load(): List<LogEntry> =
        if (file.exists()) LogbookStore.decode(file.readText()).sortedByDescending { it.timeMillis } else emptyList()

    /** Charge in progress saved by older versions. */
    private fun legacyPending(): ChargeSessionTracker.Pending? {
        val start = prefs.getLong("logbook_charge_start", -1)
        if (start < 0) return null
        val type = ChargeType.entries.firstOrNull { it.name == prefs.getString("logbook_charge_type", null) } ?: ChargeType.HOME
        return ChargeSessionTracker.Pending(start, prefs.getFloat("logbook_charge_kwh", 0f).toDouble(), type)
    }

    companion object {
        private const val KM_PER_MILE = 1.609344

        @Volatile
        private var instance: LogbookController? = null

        fun get(context: Context): LogbookController =
            instance ?: synchronized(this) { instance ?: LogbookController(context.applicationContext).also { instance = it } }
    }
}
