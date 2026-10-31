package com.splittesla.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

/**
 * Coroutine wrapper around [BluetoothGatt]. Android allows one GATT operation
 * in flight per connection, so every operation takes [mutex] and waits for
 * its callback.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onNotify: (UUID, ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
) {
    private val mutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var pending: CompletableDeferred<Any?>? = null
    private var connected: CompletableDeferred<Unit>? = null

    var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = GattException("연결 끊김 (status=$status)")
                connected?.completeExceptionally(error)
                pending?.completeExceptionally(error)
                g.close()
                gatt = null
                onDisconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status, Unit)

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            finish(BluetoothGatt.GATT_SUCCESS, newMtu)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(status, Unit)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status, Unit)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(status, value)

        @Deprecated("Used below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) finish(status, c.value ?: ByteArray(0))
        }

        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) =
            finish(status, value)

        @Deprecated("Used below API 33")
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) finish(status, d.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid, value)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onNotify(c.uuid, c.value ?: ByteArray(0))
        }
    }

    private fun finish(status: Int, value: Any?) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value) else p.completeExceptionally(GattException("GATT 오류 status=$status"))
    }

    suspend fun connect(timeoutMillis: Long = 15_000) {
        val done = CompletableDeferred<Unit>()
        connected = done
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(timeoutMillis) { done.await() }
        op { it.discoverServices() }
    }

    /**
     * Asks for long connection intervals. The car link only carries the odd
     * command, and a busy BLE link takes airtime from Bluetooth audio and
     * the Wi-Fi hotspot on the same 2.4 GHz radio.
     */
    fun preferLowPower() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
    }

    suspend fun requestMtu(size: Int): Int = (op { it.requestMtu(size) } as? Int) ?: mtu

    fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(characteristic)

    fun characteristics(service: UUID): List<BluetoothGattCharacteristic> =
        gatt?.getService(service)?.characteristics.orEmpty()

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray = op { it.readCharacteristic(c) } as ByteArray

    suspend fun readDescriptor(d: BluetoothGattDescriptor): ByteArray = op { it.readDescriptor(d) } as ByteArray

    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean = true) {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = type
                    c.value = value
                    it.writeCharacteristic(c)
                }
            }
        }
    }

    /** Turns on indications if the characteristic supports them, otherwise notifications. */
    suspend fun subscribe(c: BluetoothGattCharacteristic) {
        val g = gatt ?: throw GattException("연결 안 됨")
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    it.writeDescriptor(cccd)
                }
            }
        }
    }

    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private suspend fun op(timeoutMillis: Long = 10_000, start: (BluetoothGatt) -> Boolean): Any? = mutex.withLock {
        val g = gatt ?: throw GattException("연결 안 됨")
        val deferred = CompletableDeferred<Any?>()
        pending = deferred
        try {
            if (!start(g)) throw GattException("GATT 작업을 시작하지 못함")
            withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pending = null
        }
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val USER_DESCRIPTION: UUID = UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")
    }
}
