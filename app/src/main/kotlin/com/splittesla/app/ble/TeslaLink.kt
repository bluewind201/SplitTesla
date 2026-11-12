package com.splittesla.app.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.splittesla.core.tesla.BleDeframer
import com.splittesla.core.tesla.BleFraming
import com.splittesla.core.tesla.TeslaBleConstants
import com.splittesla.core.tesla.TeslaClient
import com.splittesla.core.tesla.TeslaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * BLE link to the car itself, carrying Tesla's vehicle-command protocol.
 * Tries the last known MAC address first for fast reconnection, falls back to
 * scanning, and retries transient GATT errors up to 3 times.
 */
class TeslaLink(
    private val context: Context,
    private val vin: String,
    private val key: TeslaKey,
    private val savedAddress: String? = null,
    private val onAddressLearned: (String) -> Unit = {},
    private val onDisconnect: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val service = UUID.fromString(TeslaBleConstants.SERVICE_UUID)
    private val toVehicle = UUID.fromString(TeslaBleConstants.TO_VEHICLE_UUID)
    private val fromVehicle = UUID.fromString(TeslaBleConstants.FROM_VEHICLE_UUID)
    private val deframer = BleDeframer()
    private var gatt: GattClient? = null

    var client: TeslaClient? = null
        private set

    suspend fun connect() {
        val localName = TeslaBleConstants.localName(vin)
        val scanner = BleScanner(context)
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val cached = if (attempt == 1) scanner.deviceByAddress(savedAddress) else null
                if (cached != null) {
                    try {
                        log("차량 빠른 재연결 시도: ${cached.address}")
                        connectToDevice(cached, timeoutMillis = 6_000, refreshCache = false)
                        onAddressLearned(cached.address)
                        return
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        disconnect()
                        log("차량 빠른 재연결 실패 (${e.message}), 스캔으로 전환")
                        delay(300)
                    }
                }

                log("차량 검색: $localName (시도 $attempt/$MAX_ATTEMPTS)")
                val others = mutableListOf<String>()
                val device = scanner.findByName(
                    localName,
                    timeoutMillis = 12_000,
                    similar = { TESLA_NAME.matches(it) },
                    onOther = { others += it; log("다른 테슬라 발견: $it") },
                ) ?: throw GattException(
                    if (others.isNotEmpty()) {
                        "근처 테슬라(${others.joinToString()})는 보이는데 이 VIN의 차($localName)는 없습니다. VIN을 확인하세요"
                    } else {
                        "테슬라 BLE 신호가 하나도 안 잡힙니다. 위치 권한·위치(GPS) 켜짐과 차와의 거리를 확인하세요"
                    },
                )
                connectToDevice(device, timeoutMillis = 12_000, refreshCache = attempt >= 2)
                onAddressLearned(device.address)
                return
            } catch (ce: CancellationException) {
                disconnect()
                throw ce
            } catch (e: Exception) {
                lastError = e
                disconnect()
                if (attempt < MAX_ATTEMPTS) {
                    log("차량 BLE 연결 재시도 ($attempt/$MAX_ATTEMPTS): ${e.message}")
                    delay(600L * attempt)
                }
            }
        }
        throw lastError ?: GattException("차량 BLE 연결 실패")
    }

    private suspend fun connectToDevice(device: BluetoothDevice, timeoutMillis: Long, refreshCache: Boolean) {
        val g = GattClient(context, device, { uuid, value -> onIndication(uuid, value) }) {
            client = null
            onDisconnect()
        }
        gatt = g
        try {
            g.connect(timeoutMillis = timeoutMillis, refreshCache = refreshCache)
            val tx = g.characteristic(service, toVehicle) ?: throw GattException("차량 쓰기 특성 없음")
            val rx = g.characteristic(service, fromVehicle) ?: throw GattException("차량 읽기 특성 없음")
            g.subscribe(rx)
            val mtu = runCatching { g.requestMtu(512) }.getOrDefault(23)
            val block = minOf(mtu, TeslaBleConstants.MAX_MESSAGE_SIZE) - 3
            log("차량 BLE 연결, MTU=$mtu")
            g.preferLowPower()

            client = TeslaClient(
                transport = { message ->
                    for (chunk in BleFraming.chunks(message, block)) g.write(tx, chunk, withResponse = true)
                },
                vin = vin,
                key = key,
            )
        } catch (t: Throwable) {
            g.close()
            if (gatt === g) gatt = null
            client = null
            throw t
        }
    }

    private fun onIndication(uuid: UUID, value: ByteArray) {
        if (uuid != fromVehicle) return
        for (message in deframer.feed(value)) client?.onMessage(message)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        /** Every Tesla advertises "S" + 16 hex digits + a letter (C for cars). */
        val TESLA_NAME = Regex("^S[0-9a-fA-F]{16}[A-Z]$")
    }

    fun disconnect() {
        gatt?.close()
        gatt = null
        client = null
    }
}
