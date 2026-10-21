package com.splittesla.core.s3xy

import java.io.ByteArrayOutputStream

// Minimal protobuf wire-format codec for the Enhauto Commander messages, which
// have no published .proto files. Ported from S3XYCommander (MIT).

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    fun varint(value: Long) {
        var x = value
        while (x and 0x7FL.inv() != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun uint32(field: Int, value: Long) { tag(field, 0); varint(value and 0xFFFFFFFFL) }
    fun int32(field: Int, value: Int) { tag(field, 0); varint(value.toLong()) }
    fun bool(field: Int, value: Boolean) { tag(field, 0); varint(if (value) 1 else 0) }
    fun bytes(field: Int, value: ByteArray) { tag(field, 2); varint(value.size.toLong()); out.write(value) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, value: ByteArray) = bytes(field, value)

    fun packedVarints(field: Int, values: List<Long>) {
        val inner = ProtoWriter()
        values.forEach { inner.varint(it) }
        bytes(field, inner.bytes())
    }
}

internal class ProtoReader(private val data: ByteArray) {
    private var i = 0

    val atEnd: Boolean get() = i >= data.size

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw ProtoException("varint overflow")
        }
        throw ProtoException("truncated")
    }

    /** Returns field number and wire type. */
    fun tag(): Pair<Int, Int> {
        val key = varint()
        return Pair((key ushr 3).toInt(), (key and 7).toInt())
    }

    fun bytes(): ByteArray {
        val len = varint().toInt()
        if (len < 0 || i + len > data.size) throw ProtoException("truncated")
        val out = data.copyOfRange(i, i + len)
        i += len
        return out
    }

    fun fixed32(): Int {
        if (i + 4 > data.size) throw ProtoException("truncated")
        val v = (data[i].toInt() and 0xFF) or
            ((data[i + 1].toInt() and 0xFF) shl 8) or
            ((data[i + 2].toInt() and 0xFF) shl 16) or
            ((data[i + 3].toInt() and 0xFF) shl 24)
        i += 4
        return v
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> { if (i + 8 > data.size) throw ProtoException("truncated"); i += 8 }
            2 -> bytes()
            5 -> fixed32()
            else -> throw ProtoException("unknown wire type $wire")
        }
    }
}

class ProtoException(message: String) : Exception(message)

/** `sint32`: zigzag encoded. */
internal fun zigzag32(v: Long): Int = zigzag64(v).toInt()

/** `sint64`: zigzag encoded. */
internal fun zigzag64(v: Long): Long = (v ushr 1) xor -(v and 1)
