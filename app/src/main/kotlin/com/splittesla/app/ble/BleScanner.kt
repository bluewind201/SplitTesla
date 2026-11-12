package com.splittesla.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Finds the first advertiser matching a service UUID or a local name, or resolves a known MAC address. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Returns a direct [BluetoothDevice] handle for a previously remembered MAC address when Bluetooth is on. */
    fun deviceByAddress(address: String?): BluetoothDevice? {
        val addr = address?.trim()?.uppercase().orEmpty()
        if (addr.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(addr)) return null
        val a = adapter ?: return null
        if (!a.isEnabled) return null
        return runCatching { a.getRemoteDevice(addr) }.getOrNull()
    }

    suspend fun findByService(service: UUID, timeoutMillis: Long = 10_000): BluetoothDevice? =
        find(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()), timeoutMillis) { result ->
            result.scanRecord?.serviceUuids?.contains(ParcelUuid(service)) == true || true
        } ?: find(emptyList(), (timeoutMillis / 2).coerceAtLeast(4_000)) { result ->
            // Fallback: some phones fail hardware UUID filtering when the UUID is in the scan response.
            result.scanRecord?.serviceUuids?.any { it.uuid == service } == true
        }

    /**
     * Matches the name in software instead of with a ScanFilter. A Tesla puts
     * its name in the scan response, and many phones' hardware filters only
     * look at the advertisement itself, so a name filter never fires.
     * [onOther] receives other names that pass [similar], for diagnosis.
     */
    suspend fun findByName(
        name: String,
        timeoutMillis: Long = 12_000,
        similar: (String) -> Boolean = { false },
        onOther: (String) -> Unit = {},
    ): BluetoothDevice? {
        val a = adapter
        if (a != null && a.isEnabled) {
            runCatching {
                a.bondedDevices?.firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
            }.getOrNull()?.let { return it }
        }
        val reported = HashSet<String>()
        return find(emptyList(), timeoutMillis) { result ->
            val seen = result.scanRecord?.deviceName ?: result.device.name
            when {
                seen == null -> false
                seen.equals(name, ignoreCase = true) -> true
                else -> {
                    if (similar(seen) && reported.add(seen)) onOther(seen)
                    false
                }
            }
        }
    }

    private suspend fun find(filters: List<ScanFilter>, timeoutMillis: Long, matches: (ScanResult) -> Boolean): BluetoothDevice? =
        scanMutex.withLock {
            val adapter = adapter
            if (adapter == null || !adapter.isEnabled) throw GattException("블루투스가 꺼져 있습니다")
            val scanner = adapter.bluetoothLeScanner ?: throw GattException("블루투스가 꺼져 있습니다")
            // With location off, Android silently returns no scan results at all.
            val location = context.getSystemService(LocationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && location?.isLocationEnabled == false) {
                throw GattException("폰의 위치(GPS)가 꺼져 있어 블루투스 검색이 안 됩니다. 빠른 설정에서 위치를 켜세요")
            }
            // Enforce a brief gap between consecutive BLE scans so Android's 5-scans-per-30s limit is not tripped.
            val sinceLast = System.currentTimeMillis() - lastScanEndAt
            if (sinceLast in 0..1_200L) delay(1_200L - sinceLast)

            val found = CompletableDeferred<BluetoothDevice>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (matches(result)) found.complete(result.device)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.firstOrNull(matches)?.let { found.complete(it.device) }
                }

                override fun onScanFailed(errorCode: Int) {
                    found.completeExceptionally(GattException("스캔 실패 code=$errorCode"))
                }
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0)
                .build()
            scanner.startScan(filters, settings, callback)
            try {
                withTimeoutOrNull(timeoutMillis) { found.await() }
            } finally {
                runCatching { scanner.stopScan(callback) }
                lastScanEndAt = System.currentTimeMillis()
            }
        }

    private companion object {
        val scanMutex = Mutex()
        @Volatile
        var lastScanEndAt = 0L
    }
}
