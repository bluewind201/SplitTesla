package com.splittesla.core.s3xy

import kotlinx.coroutines.test.runTest
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommanderTest {
    /** Device side of Security1 plus a minimal enhapi command handler. */
    private class FakeCommander(pop: String = CommanderConstants.DEFAULT_POP, val allowed: Set<String>) {
        private val popHash = MessageDigest.getInstance("SHA-256").digest(pop.toByteArray())
        private val priv = ByteArray(32).also { X25519.generatePrivateKey(SecureRandom(), it) }
        private val pub = ByteArray(32).also { X25519.generatePublicKey(priv, 0, it, 0) }
        private val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        private var clientPub = ByteArray(0)
        private var ctr: AesCtr? = null
        val commands = mutableListOf<ByteArray>()

        fun request(endpoint: String, data: ByteArray): ByteArray = when (endpoint) {
            CommanderEndpoint.SESSION -> session(data)
            CommanderEndpoint.CMD -> command(data)
            else -> error("unexpected endpoint $endpoint")
        }

        private fun session(data: ByteArray): ByteArray {
            val (msg, payload) = sec1(data)
            return if (msg == Sec1Msg.COMMAND0) {
                clientPub = field(payload, F.S1_SC0).let { field(it, F.SC0_CLIENT_PUBKEY) }
                val shared = ByteArray(32).also { X25519.calculateAgreement(priv, 0, clientPub, 0, it, 0) }
                ctr = AesCtr(ByteArray(32) { (shared[it].toInt() xor popHash[it].toInt()).toByte() }, random)
                val sr0 = ProtoWriter().apply { uint32(1, 0); bytes(2, pub); bytes(3, random) }.bytes()
                Build.sessionDataSec1(ProtoWriter().apply { uint32(F.S1_MSG, Sec1Msg.RESPONSE0); message(F.S1_SR0, sr0) }.bytes())
            } else {
                val verify = field(field(payload, F.S1_SC1), F.SC1_CLIENT_VERIFY_DATA)
                check(ctr!!.process(verify).contentEquals(pub)) { "client verify mismatch" }
                val sr1 = ProtoWriter().apply { uint32(1, 0); bytes(3, ctr!!.process(clientPub)) }.bytes()
                Build.sessionDataSec1(ProtoWriter().apply { uint32(F.S1_MSG, Sec1Msg.RESPONSE1); message(F.S1_SR1, sr1) }.bytes())
            }
        }

        private fun command(data: ByteArray): ByteArray {
            val plain = ctr!!.process(data)
            commands += plain
            val msgType = ProtoReader(plain).let { it.tag(); it.varint() }
            val ok = if (msgType == MsgType.REQ_SEND_UUID) {
                val uuid = String(field(field(plain, F.EP_REQ_SEND_UUID), F.RSU_UUID))
                uuid in allowed
            } else {
                true
            }
            val status = ProtoWriter().apply { uint32(1, if (ok) 0 else 4) }.bytes()
            val reply = ProtoWriter().apply { uint32(F.EP_MSG_TYPE, msgType + 1); message(2, status) }.bytes()
            return ctr!!.process(reply)
        }

        private fun sec1(data: ByteArray): Pair<Long, ByteArray> {
            val sec1 = field(data, F.SD_SEC1)
            val r = ProtoReader(sec1)
            var msg = 0L
            while (!r.atEnd) {
                val (f, w) = r.tag()
                if (f == F.S1_MSG) msg = r.varint() else r.skip(w)
            }
            return Pair(msg, sec1)
        }

        private fun field(data: ByteArray, number: Int): ByteArray {
            val r = ProtoReader(data)
            while (!r.atEnd) {
                val (f, w) = r.tag()
                if (f == number && w == 2) return r.bytes()
                r.skip(w)
            }
            error("field $number missing")
        }
    }

    @Test
    fun handshakeRegistrationAndActions() = runTest {
        val device = FakeCommander(allowed = setOf("{phone}"))
        val session = CommanderSession({ e, d -> device.request(e, d) }, phoneUuid = "{phone}")
        session.handshake()
        assertEquals(CommanderStatus.SUCCESS, session.sendUuid())
        assertEquals(CommanderStatus.SUCCESS, session.subscribe())
        assertEquals(CommanderStatus.SUCCESS, session.tryAction(CommanderAction.HAZARD_LIGHTS))
        assertContentEquals(hex("081cba02" + "09" + "0a07" + "080a" + "1000" + "1a0101"), device.commands.last())
    }

    @Test
    fun unknownPhoneIsRejectedUntilAuthorized() = runTest {
        val device = FakeCommander(allowed = emptySet())
        val session = CommanderSession({ e, d -> device.request(e, d) }, phoneUuid = "{other}")
        session.handshake()
        assertEquals(CommanderStatus.INVALID_ARGUMENT, session.sendUuid())
    }

    @Test
    fun wrongPopFailsVerification() = runTest {
        val device = FakeCommander(pop = "not_it", allowed = emptySet())
        val session = CommanderSession({ e, d -> device.request(e, d) }, phoneUuid = "{phone}")
        assertFailsWith<Exception> { session.handshake() }
    }

    @Test
    fun aesCtrMatchesJdkAcrossCalls() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { 0xFF.toByte() } // also exercises the counter carry
        val data = ByteArray(100) { (it * 7).toByte() }
        val jdk = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
        val ours = AesCtr(key, iv)
        val split = ours.process(data.copyOfRange(0, 5)) + ours.process(data.copyOfRange(5, 37)) + ours.process(data.copyOfRange(37, 100))
        assertContentEquals(jdk, split)
    }

    @Test
    fun decodesVehicleDataPush() {
        val holder = ProtoWriter().apply {
            uint32(61, 88) // speed
            uint32(29, 27) // battery current, sint32 zigzag for -14
            uint32(28, 38930) // 389.30 V
            uint32(66, 4) // gear D
            uint32(111, 77) // soc
            uint32(106, 1) // blind spot left
            uint32(113, 2) // front left door closed
        }.bytes()
        val push = ProtoWriter().apply { uint32(F.EPP_PUSH_TYPE, PushType.VEHICLE_DATA); message(F.EPP_VEHICLE_DATA, holder) }.bytes()
        val session = CommanderSession({ _, _ -> ByteArray(0) }, phoneUuid = "{p}")
        val delta = session.decodePush(push)!!
        assertEquals(88, delta.speedKmh)
        assertEquals(-1.4, delta.batteryAmps)
        assertEquals(389.30, delta.batteryVolts)
        assertEquals(Gear.DRIVE, delta.gear)
        assertEquals(77, delta.socPercent)
        assertEquals(true, delta.blindSpotLeft)
        assertEquals(Latch.CLOSED, delta.latch(VehicleSignal.DOOR_FRONT_LEFT))

        val merged = VehicleData(mapOf(VehicleSignal.SPEED to 10L, VehicleSignal.OUTSIDE_TEMPERATURE to 16L)).let { delta.mergedOnto(it) }
        assertEquals(88, merged.speedKmh)
        assertEquals(16, merged.outsideTempC)

        val other = ProtoWriter().apply { uint32(F.EPP_PUSH_TYPE, 3) }.bytes()
        assertNull(session.decodePush(other))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
