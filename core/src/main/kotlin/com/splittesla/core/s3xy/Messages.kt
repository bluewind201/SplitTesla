package com.splittesla.core.s3xy

// Field and message numbers reverse-engineered from the proto descriptors
// compiled into Enhauto's S3XY app (via S3XYCommander, MIT).

internal object F {
    // SessionData / Sec1Payload
    const val SD_SEC_VER = 2
    const val SD_SEC1 = 11
    const val S1_MSG = 1
    const val S1_SC0 = 20
    const val S1_SR0 = 21
    const val S1_SC1 = 22
    const val S1_SR1 = 23
    const val SC0_CLIENT_PUBKEY = 1
    const val SC0_SECURITY_FLAGS = 2
    const val SR0_STATUS = 1
    const val SR0_DEVICE_PUBKEY = 2
    const val SR0_DEVICE_RANDOM = 3
    const val SC1_CLIENT_VERIFY_DATA = 2
    const val SC1_DEVICE_TYPE = 3
    const val SR1_STATUS = 1
    const val SR1_DEVICE_VERIFY_DATA = 3

    // EnhApiPayloadHolder
    const val EP_MSG_TYPE = 1
    const val EP_REQ_SEND_UUID = 43
    const val EP_REQ_TRY_ACTION = 39
    const val EP_REQ_SUBSCRIBE_VEHICLE_DATA = 135
    const val EP_REQ_SUBSCRIBE_VEHICLE_EVENTS = 163
    const val RSU_UUID = 1
    const val RSV_SUBSCRIBE_ALL = 1
    const val RSV_UNSUBSCRIBE_ALL = 2
    const val RSV_SUBSCRIBE_FIELDS = 13
    const val RTA_CONFIG = 1
    const val PAC_ACTION_TYPE = 1
    const val PAC_SWITCH_TYPE = 2
    const val PAC_SWITCH_STATES = 3

    // EnhApiPushPayloadHolder
    const val EPP_PUSH_TYPE = 1
    const val EPP_VEHICLE_DATA = 25
    const val EPP_VEHICLE_EVENTS = 28
}

internal object MsgType {
    const val REQ_TRY_ACTION = 28L
    const val REQ_SEND_UUID = 32L
    const val REQ_SUBSCRIBE_VEHICLE_DATA = 124L
    const val REQ_SUBSCRIBE_VEHICLE_EVENTS = 152L
}

internal object PushType {
    const val VEHICLE_DATA = 16L
    const val VEHICLE_EVENTS = 19L
}

internal object Sec1Msg {
    const val COMMAND0 = 0L
    const val RESPONSE0 = 1L
    const val COMMAND1 = 2L
    const val RESPONSE1 = 3L
}

internal object SecFlags {
    const val ALL_IS_ENCRYPTED = 0L
    const val DISABLE_PUSH_ENCRYPTION = 1L
}

/** Status carried by every Resp* message. */
enum class CommanderStatus(val code: Long) {
    SUCCESS(0), INVALID_SEC_SCHEME(1), INVALID_PROTO(2), TOO_MANY_SESSIONS(3),
    INVALID_ARGUMENT(4), INTERNAL_ERROR(5), CRYPTO_ERROR(6), INVALID_SESSION(7), NOT_SUPPORTED(8);

    companion object {
        fun of(code: Long): CommanderStatus = entries.firstOrNull { it.code == code } ?: INTERNAL_ERROR
    }
}

internal object Build {
    fun sessionDataSec1(sec1Payload: ByteArray): ByteArray = ProtoWriter().apply {
        uint32(F.SD_SEC_VER, 1)
        message(F.SD_SEC1, sec1Payload)
    }.bytes()

    fun sec1Cmd0(clientPubkey: ByteArray, flags: Long): ByteArray {
        val sc0 = ProtoWriter().apply {
            bytes(F.SC0_CLIENT_PUBKEY, clientPubkey)
            uint32(F.SC0_SECURITY_FLAGS, flags)
        }
        return ProtoWriter().apply {
            uint32(F.S1_MSG, Sec1Msg.COMMAND0)
            message(F.S1_SC0, sc0.bytes())
        }.bytes()
    }

    fun sec1Cmd1(clientVerifyData: ByteArray, deviceType: Int): ByteArray {
        val sc1 = ProtoWriter().apply {
            bytes(F.SC1_CLIENT_VERIFY_DATA, clientVerifyData)
            int32(F.SC1_DEVICE_TYPE, deviceType)
        }
        return ProtoWriter().apply {
            uint32(F.S1_MSG, Sec1Msg.COMMAND1)
            message(F.S1_SC1, sc1.bytes())
        }.bytes()
    }

    fun sendUuid(uuid: String): ByteArray = envelope(
        MsgType.REQ_SEND_UUID, F.EP_REQ_SEND_UUID,
        ProtoWriter().apply { string(F.RSU_UUID, uuid) }.bytes(),
    )

    fun subscribeVehicleData(fields: List<VehicleSignal>?): ByteArray {
        val inner = ProtoWriter().apply {
            if (fields == null) bool(F.RSV_SUBSCRIBE_ALL, true)
            else packedVarints(F.RSV_SUBSCRIBE_FIELDS, fields.map { it.field.toLong() })
        }
        return envelope(MsgType.REQ_SUBSCRIBE_VEHICLE_DATA, F.EP_REQ_SUBSCRIBE_VEHICLE_DATA, inner.bytes())
    }

    fun unsubscribeAll(): ByteArray = envelope(
        MsgType.REQ_SUBSCRIBE_VEHICLE_DATA, F.EP_REQ_SUBSCRIBE_VEHICLE_DATA,
        ProtoWriter().apply { bool(F.RSV_UNSUBSCRIBE_ALL, true) }.bytes(),
    )

    fun subscribeVehicleEvents(): ByteArray = envelope(
        MsgType.REQ_SUBSCRIBE_VEHICLE_EVENTS, F.EP_REQ_SUBSCRIBE_VEHICLE_EVENTS,
        ProtoWriter().apply { bool(F.RSV_SUBSCRIBE_ALL, true) }.bytes(),
    )

    fun tryAction(action: CommanderAction, switchType: SwitchType, states: List<Long>): ByteArray {
        val config = ProtoWriter().apply {
            uint32(F.PAC_ACTION_TYPE, action.code.toLong())
            uint32(F.PAC_SWITCH_TYPE, switchType.code)
            if (states.isNotEmpty()) packedVarints(F.PAC_SWITCH_STATES, states)
        }
        val inner = ProtoWriter().apply { message(F.RTA_CONFIG, config.bytes()) }
        return envelope(MsgType.REQ_TRY_ACTION, F.EP_REQ_TRY_ACTION, inner.bytes())
    }

    private fun envelope(msgType: Long, slot: Int, inner: ByteArray): ByteArray = ProtoWriter().apply {
        uint32(F.EP_MSG_TYPE, msgType)
        message(slot, inner)
    }.bytes()
}

internal class SessionResp0(val status: Long, val devicePubkey: ByteArray, val deviceRandom: ByteArray)
internal class SessionResp1(val status: Long, val deviceVerifyData: ByteArray)

internal object Parse {
    fun sessionData(data: ByteArray): Pair<Long, ByteArray> {
        val r = ProtoReader(data)
        var sec1: ByteArray? = null
        while (!r.atEnd) {
            val (field, wire) = r.tag()
            if (field == F.SD_SEC1 && wire == 2) sec1 = r.bytes() else r.skip(wire)
        }
        val r2 = ProtoReader(sec1 ?: throw ProtoException("SessionData without sec1"))
        var msg = 0L
        var inner = ByteArray(0)
        while (!r2.atEnd) {
            val (field, wire) = r2.tag()
            when {
                field == F.S1_MSG && wire == 0 -> msg = r2.varint()
                (field == F.S1_SR0 || field == F.S1_SR1) && wire == 2 -> inner = r2.bytes()
                else -> r2.skip(wire)
            }
        }
        return Pair(msg, inner)
    }

    fun sessionResp0(data: ByteArray): SessionResp0 {
        val r = ProtoReader(data)
        var status = 0L
        var pub = ByteArray(0)
        var rnd = ByteArray(0)
        while (!r.atEnd) {
            val (field, wire) = r.tag()
            when {
                field == F.SR0_STATUS && wire == 0 -> status = r.varint()
                field == F.SR0_DEVICE_PUBKEY && wire == 2 -> pub = r.bytes()
                field == F.SR0_DEVICE_RANDOM && wire == 2 -> rnd = r.bytes()
                else -> r.skip(wire)
            }
        }
        return SessionResp0(status, pub, rnd)
    }

    fun sessionResp1(data: ByteArray): SessionResp1 {
        val r = ProtoReader(data)
        var status = 0L
        var verify = ByteArray(0)
        while (!r.atEnd) {
            val (field, wire) = r.tag()
            when {
                field == F.SR1_STATUS && wire == 0 -> status = r.varint()
                field == F.SR1_DEVICE_VERIFY_DATA && wire == 2 -> verify = r.bytes()
                else -> r.skip(wire)
            }
        }
        return SessionResp1(status, verify)
    }

    /** Decodes an EnhApiPayloadHolder response into its msgType and inner Status. */
    fun respStatus(data: ByteArray): Pair<Long, CommanderStatus> {
        val r = ProtoReader(data)
        var msg = 0L
        var inner = ByteArray(0)
        while (!r.atEnd) {
            val (field, wire) = r.tag()
            when {
                field == F.EP_MSG_TYPE && wire == 0 -> msg = r.varint()
                wire == 2 -> inner = r.bytes()
                else -> r.skip(wire)
            }
        }
        val r2 = ProtoReader(inner)
        var status = 0L
        while (!r2.atEnd) {
            val (field, wire) = r2.tag()
            if (field == 1 && wire == 0) status = r2.varint() else r2.skip(wire)
        }
        return Pair(msg, CommanderStatus.of(status))
    }

    /** Decodes an EnhApiPushPayloadHolder into push type and its payload, if one we decode. */
    fun push(data: ByteArray): Pair<Long, ByteArray?> {
        val r = ProtoReader(data)
        var type = 0L
        var payload: ByteArray? = null
        while (!r.atEnd) {
            val (field, wire) = r.tag()
            when {
                field == F.EPP_PUSH_TYPE && wire == 0 -> type = r.varint()
                (field == F.EPP_VEHICLE_DATA || field == F.EPP_VEHICLE_EVENTS) && wire == 2 -> payload = r.bytes()
                else -> r.skip(wire)
            }
        }
        return Pair(type, payload)
    }
}
