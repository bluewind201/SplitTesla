package com.splittesla.core.s3xy

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ESP-IDF protocomm Security1 session as used by the Commander:
 * X25519 ECDH, then aes_key = shared XOR SHA256(pop), then AES-256-CTR with
 * the device random as IV. After the handshake the same CTR stream carries
 * all command traffic.
 */
class Security1(pop: String, private val random: SecureRandom = SecureRandom()) {
    private val popHash = MessageDigest.getInstance("SHA-256").digest(pop.toByteArray(Charsets.UTF_8))
    private val privateKey = ByteArray(X25519.SCALAR_SIZE)
    private var ctr: AesCtr? = null
    var clientPublicKey = ByteArray(X25519.POINT_SIZE)
        private set

    val isReady: Boolean get() = ctr != null

    fun makeStep0Request(disablePushEncryption: Boolean): ByteArray {
        X25519.generatePrivateKey(random, privateKey)
        X25519.generatePublicKey(privateKey, 0, clientPublicKey, 0)
        val flags = if (disablePushEncryption) SecFlags.DISABLE_PUSH_ENCRYPTION else SecFlags.ALL_IS_ENCRYPTED
        return Build.sessionDataSec1(Build.sec1Cmd0(clientPublicKey, flags))
    }

    fun makeStep1Request(deviceStep0: ByteArray): ByteArray {
        val (msg, inner) = Parse.sessionData(deviceStep0)
        if (msg != Sec1Msg.RESPONSE0) throw HandshakeException("expected Sec1 Response0, got $msg")
        val r0 = Parse.sessionResp0(inner)
        if (r0.status != 0L) throw HandshakeException("device returned status ${CommanderStatus.of(r0.status)}")
        if (r0.devicePubkey.size != 32) throw HandshakeException("device_pubkey not 32 bytes")
        if (r0.deviceRandom.size != 16) throw HandshakeException("device_random not 16 bytes")

        val shared = ByteArray(32)
        if (!X25519.calculateAgreement(privateKey, 0, r0.devicePubkey, 0, shared, 0)) {
            throw HandshakeException("X25519 agreement failed")
        }
        val aesKey = ByteArray(32) { (shared[it].toInt() xor popHash[it].toInt()).toByte() }
        val stream = AesCtr(aesKey, r0.deviceRandom)
        ctr = stream
        val clientVerify = stream.process(r0.devicePubkey)
        return Build.sessionDataSec1(Build.sec1Cmd1(clientVerify, DEVICE_TYPE_PHONE))
    }

    fun verifyStep1(deviceStep1: ByteArray) {
        val (msg, inner) = Parse.sessionData(deviceStep1)
        if (msg != Sec1Msg.RESPONSE1) throw HandshakeException("expected Sec1 Response1, got $msg")
        val r1 = Parse.sessionResp1(inner)
        if (r1.status != 0L) throw HandshakeException("device returned status ${CommanderStatus.of(r1.status)}")
        if (r1.deviceVerifyData.size != 32) throw HandshakeException("device_verify_data not 32 bytes")
        val got = stream().process(r1.deviceVerifyData)
        if (!MessageDigest.isEqual(got, clientPublicKey)) throw HandshakeException("verify mismatch, wrong POP?")
    }

    fun encrypt(plain: ByteArray): ByteArray = stream().process(plain)

    fun decrypt(cipher: ByteArray): ByteArray = stream().process(cipher)

    private fun stream(): AesCtr = ctr ?: throw HandshakeException("session not established")

    companion object {
        const val DEVICE_TYPE_PHONE = 1
    }
}

class HandshakeException(message: String) : Exception(message)
