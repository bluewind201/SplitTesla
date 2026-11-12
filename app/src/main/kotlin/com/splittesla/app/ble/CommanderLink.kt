package com.splittesla.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.splittesla.core.s3xy.CommanderConstants
import com.splittesla.core.s3xy.CommanderEndpoint
import com.splittesla.core.s3xy.CommanderSession
import com.splittesla.core.s3xy.VehicleData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * BLE link to the Enhance S3XY Commander. Characteristics are identified by
 * their user-description descriptor (protocomm endpoint names).
 * Tries the last known MAC address first for fast reconnection, falls back to
 * scanning, and retries transient GATT/handshake errors up to 3 times.
 */
class CommanderLink(
    private val context: Context,
    private val phoneUuid: String,
    private val savedAddress: String? = null,
    private val onAddressLearned: (String) -> Unit = {},
    private val onData: (VehicleData) -> Unit,
    private val onRawPush: (ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val serviceUuid = UUID.fromString(CommanderConstants.SERVICE_UUID)
    private var gatt: GattClient? = null
    private val endpoints = HashMap<String, BluetoothGattCharacteristic>()
    private var pushUuid: UUID? = null

    var session: CommanderSession? = null
        private set

    suspend fun connect() {
        val scanner = BleScanner(context)
        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                // On attempt 1, try direct connection to the remembered MAC address first.
                val cached = if (attempt == 1) scanner.deviceByAddress(savedAddress) else null
                if (cached != null) {
                    try {
                        log("Commander 빠른 재연결 시도: ${cached.address}")
                        connectToDevice(cached, timeoutMillis = 6_000, refreshCache = false)
                        onAddressLearned(cached.address)
                        return
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        disconnect()
                        log("Commander 빠른 재연결 실패 (${e.message}), 스캔으로 전환")
                        delay(300)
                    }
                }

                val device = scanner.findByService(serviceUuid, timeoutMillis = 10_000)
                    ?: throw GattException("Commander를 찾지 못했습니다. 차량 전원과 거리를 확인하세요")
                log("Commander 발견: ${device.address} (시도 $attempt/$MAX_ATTEMPTS)")
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
                    log("Commander 연결 재시도 ($attempt/$MAX_ATTEMPTS): ${e.message}")
                    delay(600L * attempt)
                }
            }
        }
        throw lastError ?: GattException("Commander 연결 실패")
    }

    private suspend fun connectToDevice(device: BluetoothDevice, timeoutMillis: Long, refreshCache: Boolean) {
        val client = GattClient(context, device, ::handleNotify) {
            session = null
            onDisconnect()
        }
        gatt = client
        try {
            client.connect(timeoutMillis = timeoutMillis, refreshCache = refreshCache)
            runCatching { client.requestMtu(512) }
            log("Commander 연결, MTU=${client.mtu}")

            endpoints.clear()
            for (c in client.characteristics(serviceUuid)) {
                val desc = c.getDescriptor(GattClient.USER_DESCRIPTION) ?: continue
                val name = String(client.readDescriptor(desc), Charsets.UTF_8).trim { it <= ' ' }
                endpoints[name] = c
            }
            if (endpoints.isEmpty()) throw GattException("Commander 서비스 엔드포인트를 찾지 못했습니다")
            log("Commander 엔드포인트: ${endpoints.keys.sorted()}")
            val push = endpoints[CommanderEndpoint.PUSH] ?: throw GattException("push-enhapi 엔드포인트 없음")
            pushUuid = push.uuid
            client.subscribe(push)

            val s = CommanderSession(
                transport = { endpoint, data ->
                    val c = endpoints[endpoint] ?: throw GattException("$endpoint 엔드포인트 없음")
                    client.write(c, data, withResponse = true)
                    client.read(c)
                },
                phoneUuid = phoneUuid,
            )
            s.handshake()
            log("Commander 보안 세션 수립")
            session = s
        } catch (t: Throwable) {
            client.close()
            if (gatt === client) gatt = null
            session = null
            throw t
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        if (uuid != pushUuid) return
        onRawPush(value)
        val delta = runCatching { session?.decodePush(value) }.getOrNull() ?: return
        onData(delta)
    }

    fun disconnect() {
        gatt?.close()
        gatt = null
        session = null
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
