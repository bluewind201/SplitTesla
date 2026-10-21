package com.splittesla.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Finds the first advertiser matching a service UUID or a local name. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter

    suspend fun findByService(service: UUID, timeoutMillis: Long = 15_000): BluetoothDevice? =
        find(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build(), timeoutMillis)

    suspend fun findByName(name: String, timeoutMillis: Long = 15_000): BluetoothDevice? =
        find(ScanFilter.Builder().setDeviceName(name).build(), timeoutMillis)

    private suspend fun find(filter: ScanFilter, timeoutMillis: Long): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: throw GattException("블루투스가 꺼져 있습니다")
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(GattException("스캔 실패 code=$errorCode"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        return try {
            withTimeoutOrNull(timeoutMillis) { found.await() }
        } finally {
            scanner.stopScan(callback)
        }
    }
}
