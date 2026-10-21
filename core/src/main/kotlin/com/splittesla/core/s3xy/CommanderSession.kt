package com.splittesla.core.s3xy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATT endpoints the Commander exposes. Each characteristic is named by its
 * 0x2901 user description descriptor.
 */
object CommanderEndpoint {
    const val SESSION = "prov-session"
    const val PROTO_VER = "proto-ver"
    const val CMD = "enhapi"
    const val PUSH = "push-enhapi"
}

object CommanderConstants {
    /** Primary GATT service advertised by every Commander. */
    const val SERVICE_UUID = "5857a678-87c6-11eb-8dcd-0242ac130003"

    /** Proof-of-possession for Security1, fixed in Enhauto firmware. */
    const val DEFAULT_POP = "s3xy_enh"
}

/**
 * Transport for protocomm: write a request to an endpoint with response,
 * then read the same characteristic for the answer.
 */
fun interface CommanderTransport {
    suspend fun request(endpoint: String, data: ByteArray): ByteArray
}

/**
 * Transport-independent Commander session: Security1 handshake, phone
 * registration, subscriptions and actions. The Android layer supplies the
 * BLE transport and feeds push notifications to [decodePush].
 */
class CommanderSession(
    private val transport: CommanderTransport,
    private val phoneUuid: String,
    pop: String = CommanderConstants.DEFAULT_POP,
    private val disablePushEncryption: Boolean = true,
) {
    private val security = Security1(pop)
    private val mutex = Mutex()

    val isReady: Boolean get() = security.isReady

    suspend fun handshake() = mutex.withLock {
        val step0 = security.makeStep0Request(disablePushEncryption)
        val resp0 = transport.request(CommanderEndpoint.SESSION, step0)
        val step1 = security.makeStep1Request(resp0)
        val resp1 = transport.request(CommanderEndpoint.SESSION, step1)
        security.verifyStep1(resp1)
    }

    /**
     * Registers this phone. Anything but SUCCESS means the phone is not on
     * the Commander's allowlist yet: the driver must double-press the right
     * steering-wheel scroll button, then call this again.
     */
    suspend fun sendUuid(): CommanderStatus = command(Build.sendUuid(phoneUuid))

    /** Subscribes to the given signals, or all when [fields] is null. */
    suspend fun subscribe(fields: List<VehicleSignal>? = null): CommanderStatus =
        command(Build.subscribeVehicleData(fields))

    suspend fun unsubscribeAll(): CommanderStatus = command(Build.unsubscribeAll())

    suspend fun tryAction(
        action: CommanderAction,
        switchType: SwitchType = SwitchType.Toggle,
        states: List<Long> = listOf(1),
    ): CommanderStatus = command(Build.tryAction(action, switchType, states))

    /** Sends a raw EnhApiPayloadHolder and returns the decrypted reply, for exploration. */
    suspend fun rawCommand(plain: ByteArray): ByteArray = mutex.withLock {
        security.decrypt(transport.request(CommanderEndpoint.CMD, security.encrypt(plain)))
    }

    /** Decodes a push-enhapi notification. Returns null for push types we do not model. */
    fun decodePush(data: ByteArray): VehicleData? {
        // Pushes are plaintext when push encryption is disabled, which is the default.
        if (!disablePushEncryption) return null
        val (type, payload) = Parse.push(data)
        if (type != PushType.VEHICLE_DATA || payload == null) return null
        return VehicleDataDecoder.decode(payload)
    }

    private suspend fun command(plain: ByteArray): CommanderStatus {
        val reply = rawCommand(plain)
        return Parse.respStatus(reply).second
    }
}
