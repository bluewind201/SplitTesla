package com.splittesla.core.tesla

/** Tesla BLE GATT identifiers from protocol.md. */
object TeslaBleConstants {
    const val SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e"
    const val TO_VEHICLE_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e"
    const val FROM_VEHICLE_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e"
    const val MAX_MESSAGE_SIZE = 1024

    /** Advertised local name: "S" + hex(SHA1(VIN)[:8]) + "C". */
    fun localName(vin: String): String {
        val digest = Crypto.sha1(vin.toByteArray(Charsets.US_ASCII))
        return "S" + digest.copyOf(8).joinToString("") { "%02x".format(it) } + "C"
    }
}

/** Each BLE message is preceded by its two-byte big-endian length. */
object BleFraming {
    fun encode(message: ByteArray): ByteArray {
        require(message.size <= TeslaBleConstants.MAX_MESSAGE_SIZE) { "message too large for BLE" }
        return byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message
    }

    /** Splits a framed message into writes of at most [blockLength] bytes. */
    fun chunks(message: ByteArray, blockLength: Int): List<ByteArray> {
        val framed = encode(message)
        return (framed.indices step blockLength).map { framed.copyOfRange(it, minOf(it + blockLength, framed.size)) }
    }
}

/** Reassembles framed messages from indication fragments. */
class BleDeframer(private val rxTimeoutMillis: Long = 1000, private val clock: () -> Long = System::currentTimeMillis) {
    private var buffer = ByteArray(0)
    private var lastRx = 0L

    /** Feeds one fragment and returns every message it completes. */
    fun feed(fragment: ByteArray): List<ByteArray> {
        val now = clock()
        if (now - lastRx > rxTimeoutMillis) buffer = ByteArray(0)
        lastRx = now
        buffer += fragment
        val messages = mutableListOf<ByteArray>()
        while (buffer.size >= 2) {
            val length = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            if (length > TeslaBleConstants.MAX_MESSAGE_SIZE) {
                buffer = ByteArray(0)
                break
            }
            if (buffer.size < 2 + length) break
            messages += buffer.copyOfRange(2, 2 + length)
            buffer = buffer.copyOfRange(2 + length, buffer.size)
        }
        return messages
    }
}
