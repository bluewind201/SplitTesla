package com.splittesla.app.ble

import android.content.Context
import com.splittesla.core.tesla.BleDeframer
import com.splittesla.core.tesla.BleFraming
import com.splittesla.core.tesla.TeslaBleConstants
import com.splittesla.core.tesla.TeslaClient
import com.splittesla.core.tesla.TeslaKey
import java.util.UUID

/** BLE link to the car itself, carrying Tesla's vehicle-command protocol. */
class TeslaLink(
    private val context: Context,
    private val vin: String,
    private val key: TeslaKey,
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
        log("차량 검색: $localName")
        val device = BleScanner(context).findByName(localName)
            ?: throw GattException("차량 BLE를 찾지 못했습니다 ($localName). VIN과 거리를 확인하세요")
        val g = GattClient(context, device, { uuid, value -> onIndication(uuid, value) }) {
            client = null
            onDisconnect()
        }
        gatt = g
        g.connect()
        val tx = g.characteristic(service, toVehicle) ?: throw GattException("차량 쓰기 특성 없음")
        val rx = g.characteristic(service, fromVehicle) ?: throw GattException("차량 읽기 특성 없음")
        g.subscribe(rx)
        val mtu = runCatching { g.requestMtu(512) }.getOrDefault(23)
        val block = minOf(mtu, TeslaBleConstants.MAX_MESSAGE_SIZE) - 3
        log("차량 BLE 연결, MTU=$mtu")

        client = TeslaClient(
            transport = { message ->
                for (chunk in BleFraming.chunks(message, block)) g.write(tx, chunk, withResponse = true)
            },
            vin = vin,
            key = key,
        )
    }

    private fun onIndication(uuid: UUID, value: ByteArray) {
        if (uuid != fromVehicle) return
        for (message in deframer.feed(value)) client?.onMessage(message)
    }

    fun disconnect() {
        gatt?.close()
        gatt = null
        client = null
    }
}
