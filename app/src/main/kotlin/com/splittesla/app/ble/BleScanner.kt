package com.splittesla.app.ble

import android.annotation.SuppressLint
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Finds the first advertiser matching a service UUID or a local name. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter

    suspend fun findByService(service: UUID, timeoutMillis: Long = 15_000): BluetoothDevice? =
        find(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()), timeoutMillis) { true }

    /**
     * Matches the name in software instead of with a ScanFilter. A Tesla puts
     * its name in the scan response, and many phones' hardware filters only
     * look at the advertisement itself, so a name filter never fires.
     * [onOther] receives other names that pass [similar], for diagnosis.
     */
    suspend fun findByName(
        name: String,
        timeoutMillis: Long = 20_000,
        similar: (String) -> Boolean = { false },
        onOther: (String) -> Unit = {},
    ): BluetoothDevice? {
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

    private suspend fun find(filters: List<ScanFilter>, timeoutMillis: Long, matches: (ScanResult) -> Boolean): BluetoothDevice? {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) throw GattException("블루투스가 꺼져 있습니다")
        val scanner = adapter.bluetoothLeScanner ?: throw GattException("블루투스가 꺼져 있습니다")
        // With location off, Android silently returns no scan results at all.
        val location = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && location?.isLocationEnabled == false) {
            throw GattException("폰의 위치(GPS)가 꺼져 있어 블루투스 검색이 안 됩니다. 빠른 설정에서 위치를 켜세요")
        }
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (matches(result)) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(GattException("스캔 실패 code=$errorCode"))
            }
        }
        // Not LOW_LATENCY: a constant full-time scan shares the 2.4 GHz radio with
        // Bluetooth audio and the phone's Wi-Fi hotspot, and made YouTube stutter.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(filters, settings, callback)
        return try {
            withTimeoutOrNull(timeoutMillis) { found.await() }
        } finally {
            scanner.stopScan(callback)
        }
    }
}
