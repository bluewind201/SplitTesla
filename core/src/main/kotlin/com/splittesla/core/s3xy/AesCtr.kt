package com.splittesla.core.s3xy

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CTR stream with a 128-bit big-endian counter. The keystream position is
 * kept across calls, so every encrypt and decrypt advances one shared stream,
 * exactly as the Commander firmware does.
 */
internal class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private val counter = iv.copyOf()
    private var keystream = ByteArray(16)
    private var used = 16

    init {
        require(iv.size == 16) { "AES-CTR IV must be 16 bytes" }
    }

    fun process(input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        for (n in input.indices) {
            if (used == 16) {
                keystream = cipher.doFinal(counter)
                increment()
                used = 0
            }
            out[n] = (input[n].toInt() xor keystream[used++].toInt()).toByte()
        }
        return out
    }

    private fun increment() {
        for (k in 15 downTo 0) {
            counter[k] = (counter[k] + 1).toByte()
            if (counter[k].toInt() != 0) return
        }
    }
}
