package com.splittesla.core.tesla

import com.tesla.generated.signatures.Signatures
import java.io.ByteArrayOutputStream

/**
 * TLV metadata serialization from Tesla's protocol.md: tags in ascending
 * order, each as tag, length, value, then TAG_END, optionally followed by the
 * message; hashed with SHA-256 or HMAC-SHA256.
 */
internal class Metadata(private val hmacKey: ByteArray? = null) {
    private val out = ByteArrayOutputStream()
    private var lastTag = -1

    fun add(tag: Signatures.Tag, value: ByteArray): Metadata {
        val raw = tag.number
        require(raw >= lastTag) { "metadata tags must be added in ascending order" }
        require(value.size <= 255) { "metadata field is longer than 255 bytes" }
        lastTag = raw
        out.write(raw)
        out.write(value.size)
        out.write(value)
        return this
    }

    fun addUInt32(tag: Signatures.Tag, value: Long): Metadata = add(tag, uint32BigEndian(value))

    fun serialized(): ByteArray = out.toByteArray() + byteArrayOf(Signatures.Tag.TAG_END_VALUE.toByte())

    fun checksum(message: ByteArray? = null): ByteArray {
        val data = serialized() + (message ?: ByteArray(0))
        return if (hmacKey != null) Crypto.hmacSha256(hmacKey, data) else Crypto.sha256(data)
    }
}

internal fun uint32BigEndian(value: Long): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)
