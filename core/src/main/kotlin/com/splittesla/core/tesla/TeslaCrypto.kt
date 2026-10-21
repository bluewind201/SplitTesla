package com.splittesla.core.tesla

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The phone's P-256 key, enrolled on the car as a virtual key. */
class TeslaKey private constructor(val privateKey: ECPrivateKey, val publicKey: ECPublicKey) {

    /** Uncompressed SEC1 encoding (65 bytes), as the vehicle expects it. */
    val publicKeyBytes: ByteArray get() = encodePublic(publicKey)

    /** PKCS#8 encoding for storage. */
    val encoded: ByteArray get() = privateKey.encoded

    /** K = SHA1(ECDH(ours, theirs).x)[:16], the per-domain session key. */
    fun sharedKey(peerPublic: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodePublic(peerPublic), true)
        val shared = agreement.generateSecret()
        return MessageDigest.getInstance("SHA-1").digest(shared).copyOf(16)
    }

    companion object {
        private val params: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(ECParameterSpec::class.java)
            }
        }

        fun generate(random: SecureRandom = SecureRandom()): TeslaKey {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"), random)
            val pair = gen.generateKeyPair()
            return TeslaKey(pair.private as ECPrivateKey, pair.public as ECPublicKey)
        }

        /** Restores a stored key from its PKCS#8 private part and 65-byte public part. */
        fun fromEncoded(privatePkcs8: ByteArray, publicBytes: ByteArray): TeslaKey {
            val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privatePkcs8)) as ECPrivateKey
            return TeslaKey(private, decodePublic(publicBytes) as ECPublicKey)
        }

        /** Builds a key from a raw private scalar and its public point (test vectors). */
        fun fromScalar(s: BigInteger, publicBytes: ByteArray): TeslaKey {
            val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(s, params)) as ECPrivateKey
            return TeslaKey(private, decodePublic(publicBytes) as ECPublicKey)
        }

        fun decodePublic(bytes: ByteArray): PublicKey {
            require(bytes.size == 65 && bytes[0] == 4.toByte()) { "expected uncompressed P-256 point" }
            val x = BigInteger(1, bytes.copyOfRange(1, 33))
            val y = BigInteger(1, bytes.copyOfRange(33, 65))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        }

        fun encodePublic(key: ECPublicKey): ByteArray =
            byteArrayOf(4) + key.w.affineX.toFixed(32) + key.w.affineY.toFixed(32)
    }
}

internal fun BigInteger.toFixed(size: Int): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == size -> raw
        raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
        else -> ByteArray(size - raw.size) + raw
    }
}

internal object Crypto {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

    class Sealed(val nonce: ByteArray, val ciphertext: ByteArray, val tag: ByteArray)

    fun gcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val out = cipher.doFinal(plaintext)
        return Sealed(nonce, out.copyOfRange(0, out.size - 16), out.copyOfRange(out.size - 16, out.size))
    }

    fun gcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext + tag)
    }
}
