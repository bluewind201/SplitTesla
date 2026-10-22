package com.splittesla.app.logbook

import android.content.Context
import android.net.Uri
import com.splittesla.core.logbook.ChargeSessionTracker
import com.splittesla.core.logbook.ChargeType
import com.splittesla.core.logbook.EntryKind
import com.splittesla.core.logbook.LogEntry
import com.splittesla.core.logbook.Logbook
import com.splittesla.core.logbook.LogbookStore
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
)

/**
 * The 차계부: costs, charges and services, kept in app storage. Charges
 * record themselves from the car's charge state, priced with the user's
 * per-kWh rates, and every entry is prefilled with the car's odometer.
 */
class LogbookController(private val context: Context, private val log: (String) -> Unit) {
    private val file get() = File(context.filesDir, "logbook.tsv")
    private val prefs = context.getSharedPreferences("splittesla", Context.MODE_PRIVATE)
    private val tracker = ChargeSessionTracker(loadPending())

    private val _state = MutableStateFlow(
        LogbookState(
            entries = load(),
            odometerKm = prefs.getInt("logbook_odometer", -1).takeIf { it >= 0 },
            prices = ChargeType.entries.associateWith { prefs.getInt("logbook_price_${it.name}", it.defaultWonPerKwh) },
            charging = tracker.current,
        ),
    )
    val state: StateFlow<LogbookState> = _state.asStateFlow()

    fun save(entry: LogEntry) {
        val id = if (entry.id == 0L) System.currentTimeMillis() else entry.id
        val next = (_state.value.entries.filter { it.id != id } + entry.copy(id = id)).sortedByDescending { it.timeMillis }
        persist(next)
        entry.odometerKm?.let { km -> if (km > (_state.value.odometerKm ?: 0) && !_state.value.odometerFromCar) setOdometer(km, false) }
    }

    fun delete(id: Long) = persist(_state.value.entries.filter { it.id != id })

    fun setPrice(type: ChargeType, wonPerKwh: Int) {
        prefs.edit().putInt("logbook_price_${type.name}", wonPerKwh).apply()
        _state.update { it.copy(prices = it.prices + (type to wonPerKwh)) }
    }

    fun onOdometer(km: Double) = setOdometer(km.toInt(), true)

    private fun setOdometer(km: Int, fromCar: Boolean) {
        prefs.edit().putInt("logbook_odometer", km).apply()
        _state.update { it.copy(odometerKm = km, odometerFromCar = fromCar || it.odometerFromCar) }
    }

    /** Feed every charge-state reading from the car here. */
    fun onChargeState(charge: Vehicle.ChargeState) {
        val name = charge.chargingState.typeCase.name
        val charging = name == "CHARGING" || name == "STARTING"
        val type = when {
            !charge.hasFastChargerPresent() || !charge.fastChargerPresent -> ChargeType.HOME
            charge.fastChargerType.typeCase.name == "SUPERCHARGER" || charge.fastChargerType.typeCase.name == "TESLA" -> ChargeType.SUPERCHARGER
            else -> ChargeType.FAST
        }
        val kwh = charge.chargeEnergyAdded.toDouble().takeIf { charge.hasChargeEnergyAdded() }
        val session = tracker.feed(charging, kwh, type, System.currentTimeMillis())
        savePending()
        _state.update { it.copy(charging = tracker.current) }
        if (session != null) {
            val price = _state.value.prices[session.type] ?: session.type.defaultWonPerKwh
            save(
                LogEntry(
                    id = 0,
                    kind = EntryKind.CHARGE,
                    timeMillis = session.endMillis,
                    costWon = Math.round(session.kwh * price),
                    odometerKm = _state.value.odometerKm,
                    kwh = session.kwh,
                    chargeType = session.type,
                    memo = "자동 기록 · 단가 ${price}원/kWh",
                    auto = true,
                ),
            )
            log("차계부: 충전 ${"%.1f".format(session.kwh)} kWh 자동 기록")
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

    private fun loadPending(): ChargeSessionTracker.Pending? {
        val start = prefs.getLong("logbook_charge_start", -1)
        if (start < 0) return null
        val type = ChargeType.entries.firstOrNull { it.name == prefs.getString("logbook_charge_type", null) } ?: ChargeType.HOME
        return ChargeSessionTracker.Pending(start, prefs.getFloat("logbook_charge_kwh", 0f).toDouble(), type)
    }

    private fun savePending() {
        val p = tracker.current
        prefs.edit().apply {
            if (p == null) {
                remove("logbook_charge_start"); remove("logbook_charge_kwh"); remove("logbook_charge_type")
            } else {
                putLong("logbook_charge_start", p.startMillis)
                putFloat("logbook_charge_kwh", p.kwh.toFloat())
                putString("logbook_charge_type", p.type.name)
            }
        }.apply()
    }
}
