package com.splittesla.core.tesla

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage
import java.security.MessageDigest

/**
 * Authenticated session with one vehicle domain (VCSEC or infotainment),
 * following pkg/protocol/protocol.md in teslamotors/vehicle-command.
 */
internal class TeslaSession private constructor(
    private val key: TeslaKey,
    private val verifierName: ByteArray,
    private val sessionKey: ByteArray,
    private val vehiclePublicKey: ByteArray,
    info: Signatures.SessionInfo,
    nowMillis: Long,
) {
    var counter: Long = info.counter.toLong() and 0xFFFFFFFFL
        private set
    private var epoch: ByteArray = info.epoch.toByteArray()
    private var timeZeroMillis: Long = nowMillis - clockOf(info) * 1000
    private var setTime: Long = clockOf(info)

    /** Vehicle clock in seconds, relative to the session epoch. */
    fun timestamp(nowMillis: Long): Long = maxOf(0L, (nowMillis - timeZeroMillis) / 1000)

    /** Encrypts [builder]'s payload in place and adds the AES-GCM signature data. */
    fun authorize(
        builder: RoutableMessage.Builder,
        expiresInSeconds: Long,
        nowMillis: Long,
        nonce: ByteArray = Crypto.randomBytes(12),
    ) {
        check(counter < 0xFFFFFFFFL) { "session counter rolled over" }
        counter += 1
        val expiresAt = timestamp(nowMillis) + expiresInSeconds
        val meta = Metadata()
            .add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()))
            .add(Signatures.Tag.TAG_DOMAIN, byteArrayOf(builder.toDestination.domainValue.toByte()))
            .add(Signatures.Tag.TAG_PERSONALIZATION, verifierName)
            .add(Signatures.Tag.TAG_EPOCH, epoch)
            .addUInt32(Signatures.Tag.TAG_EXPIRES_AT, expiresAt)
            .addUInt32(Signatures.Tag.TAG_COUNTER, counter)
        if (builder.flags != 0) meta.addUInt32(Signatures.Tag.TAG_FLAGS, builder.flags.toLong() and 0xFFFFFFFFL)

        val sealed = Crypto.gcmSeal(sessionKey, nonce, builder.protobufMessageAsBytes.toByteArray(), meta.checksum())
        val gcm = Signatures.AES_GCM_Personalized_Signature_Data.newBuilder()
            .setEpoch(ByteString.copyFrom(epoch))
            .setNonce(ByteString.copyFrom(sealed.nonce))
            .setCounter(counter.toInt())
            .setExpiresAt(expiresAt.toInt())
            .setTag(ByteString.copyFrom(sealed.tag))
        builder.setSignatureData(
            Signatures.SignatureData.newBuilder()
                .setSignerIdentity(Signatures.KeyIdentity.newBuilder().setPublicKey(ByteString.copyFrom(key.publicKeyBytes)))
                .setAESGCMPersonalizedData(gcm),
        )
        builder.setProtobufMessageAsBytes(ByteString.copyFrom(sealed.ciphertext))
    }

    /** One byte of signature type followed by the request's tag; ties a response to its request. */
    fun requestHash(message: RoutableMessage): ByteArray {
        val sig = message.signatureData
        return when {
            sig.hasAESGCMPersonalizedData() ->
                byteArrayOf(Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED_VALUE.toByte()) +
                    sig.aesgcmPersonalizedData.tag.toByteArray()
            sig.hasHMACPersonalizedData() -> {
                var tag = sig.hmacPersonalizedData.tag.toByteArray()
                if (message.toDestination.domainValue == DOMAIN_VCSEC && tag.size > 16) tag = tag.copyOf(16)
                byteArrayOf(Signatures.SignatureType.SIGNATURE_TYPE_HMAC_PERSONALIZED_VALUE.toByte()) + tag
            }
            else -> ByteArray(0)
        }
    }

    /** Decrypts an AES-GCM response. Returns the plaintext and the response counter. */
    fun decrypt(message: RoutableMessage, requestHash: ByteArray): Pair<ByteArray, Long> {
        val gcm = message.signatureData.aesgcmResponseData
        val counter = gcm.counter.toLong() and 0xFFFFFFFFL
        val aad = Metadata()
            .add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE_VALUE.toByte()))
            .add(Signatures.Tag.TAG_DOMAIN, byteArrayOf(message.fromDestination.domainValue.toByte()))
            .add(Signatures.Tag.TAG_PERSONALIZATION, verifierName)
            .addUInt32(Signatures.Tag.TAG_COUNTER, counter)
            .addUInt32(Signatures.Tag.TAG_FLAGS, message.flags.toLong() and 0xFFFFFFFFL)
            .add(Signatures.Tag.TAG_REQUEST_HASH, requestHash)
            .addUInt32(Signatures.Tag.TAG_FAULT, message.signedMessageStatus.signedMessageFaultValue.toLong())
            .checksum()
        val plain = Crypto.gcmOpen(
            sessionKey, gcm.nonce.toByteArray(), message.protobufMessageAsBytes.toByteArray(), gcm.tag.toByteArray(), aad,
        )
        return Pair(plain, counter)
    }

    /** Applies session info a vehicle attaches to a response (for example after a counter fault). */
    fun updateSignedSessionInfo(challenge: ByteArray, encodedInfo: ByteArray, tag: ByteArray, nowMillis: Long) {
        verifySessionInfo(sessionKey, verifierName, challenge, encodedInfo, tag)
        val info = Signatures.SessionInfo.parseFrom(encodedInfo)
        if (!info.publicKey.toByteArray().contentEquals(vehiclePublicKey)) {
            throw TeslaProtocolException("vehicle public key changed unexpectedly")
        }
        val clock = clockOf(info)
        val newEpoch = info.epoch.toByteArray()
        if (!newEpoch.contentEquals(epoch) || setTime <= clock) {
            counter = maxOf(counter, info.counter.toLong() and 0xFFFFFFFFL)
            epoch = newEpoch
            setTime = clock
            timeZeroMillis = nowMillis - clock * 1000
        }
    }

    companion object {
        const val DOMAIN_VCSEC = 2

        /** Builds a session from a handshake response after checking its HMAC tag. */
        fun fromHandshake(
            key: TeslaKey,
            vin: String,
            challenge: ByteArray,
            encodedInfo: ByteArray,
            tag: ByteArray,
            nowMillis: Long,
        ): TeslaSession {
            val info = Signatures.SessionInfo.parseFrom(encodedInfo)
            if (info.status == Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST) {
                throw KeyNotPairedException()
            }
            val vehiclePublic = info.publicKey.toByteArray()
            val sessionKey = key.sharedKey(vehiclePublic)
            val verifierName = vin.toByteArray(Charsets.US_ASCII)
            verifySessionInfo(sessionKey, verifierName, challenge, encodedInfo, tag)
            return TeslaSession(key, verifierName, sessionKey, vehiclePublic, info, nowMillis)
        }

        private fun verifySessionInfo(
            sessionKey: ByteArray,
            verifierName: ByteArray,
            challenge: ByteArray,
            encodedInfo: ByteArray,
            tag: ByteArray,
        ) {
            val subkey = Crypto.hmacSha256(sessionKey, "session info".toByteArray(Charsets.US_ASCII))
            val expected = Metadata(hmacKey = subkey)
                .add(Signatures.Tag.TAG_SIGNATURE_TYPE, byteArrayOf(Signatures.SignatureType.SIGNATURE_TYPE_HMAC_VALUE.toByte()))
                .add(Signatures.Tag.TAG_PERSONALIZATION, verifierName)
                .add(Signatures.Tag.TAG_CHALLENGE, challenge)
                .checksum(encodedInfo)
            if (!MessageDigest.isEqual(expected, tag)) throw TeslaProtocolException("session info HMAC is invalid")
        }

        private fun clockOf(info: Signatures.SessionInfo): Long = info.clockTime.toLong() and 0xFFFFFFFFL
    }
}

open class TeslaProtocolException(message: String) : Exception(message)

class KeyNotPairedException : TeslaProtocolException("이 폰의 키가 차량에 등록되어 있지 않습니다")
