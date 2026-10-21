package com.splittesla.app.ble

import android.content.Context
import com.splittesla.core.s3xy.CommanderConstants
import com.splittesla.core.s3xy.CommanderEndpoint
import com.splittesla.core.s3xy.CommanderSession
import com.splittesla.core.s3xy.VehicleData
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/**
 * BLE link to the Enhance S3XY Commander. Characteristics are identified by
 * their user-description descriptor (protocomm endpoint names).
 */
class CommanderLink(
    private val context: Context,
    private val phoneUuid: String,
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
        val device = BleScanner(context).findByService(serviceUuid)
            ?: throw GattException("Commander를 찾지 못했습니다. 차량 전원과 거리를 확인하세요")
        log("Commander 발견: ${device.address}")
        val client = GattClient(context, device, ::handleNotify) {
            session = null
            onDisconnect()
        }
        gatt = client
        client.connect()
        client.requestMtu(512)
        log("Commander 연결, MTU=${client.mtu}")

        endpoints.clear()
        for (c in client.characteristics(serviceUuid)) {
            val desc = c.getDescriptor(GattClient.USER_DESCRIPTION) ?: continue
            val name = String(client.readDescriptor(desc), Charsets.UTF_8).trim { it <= ' ' }
            endpoints[name] = c
        }
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
}
