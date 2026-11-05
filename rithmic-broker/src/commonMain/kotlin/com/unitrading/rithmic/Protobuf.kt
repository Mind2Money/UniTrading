package com.unitrading.rithmic

/**
 * Minimal proto3 wire-format codec for the R | Protocol API. Rithmic messages only use varint,
 * 64-bit (double) and length-delimited (string) fields, so no generated code is needed.
 */
internal class ProtoWriter {
    private var buffer = ByteArray(256)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }
    private fun byte(value: Int) { ensure(1); buffer[size++] = value.toByte() }
    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            byte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        byte(v.toInt())
    }
    private fun key(field: Int, wireType: Int) {
        require(field in 1..536_870_911)
        varint((field.toLong() shl 3) or wireType.toLong())
    }

    /** Negative int32 values use the sign-extended ten-byte form, like protoc. */
    fun int32(field: Int, value: Int) = apply { key(field, 0); varint(value.toLong()) }
    fun uint32(field: Int, value: Long) = apply { require(value in 0..0xFFFF_FFFFL); key(field, 0); varint(value) }
    fun int64(field: Int, value: Long) = apply { key(field, 0); varint(value) }
    fun bool(field: Int, value: Boolean) = apply { key(field, 0); varint(if (value) 1 else 0) }
    fun double(field: Int, value: Double) = apply {
        key(field, 1)
        val bits = value.toRawBits()
        ensure(8)
        for (i in 0 until 8) buffer[size++] = (bits ushr (8 * i)).toByte()
    }
    fun string(field: Int, value: String) = apply {
        val bytes = value.encodeToByteArray()
        key(field, 2)
        varint(bytes.size.toLong())
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }
    fun strings(field: Int, values: Iterable<String>) = apply { values.forEach { string(field, it) } }
    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

internal sealed class ProtoValue {
    class Varint(val value: Long) : ProtoValue()
    class Fixed64(val value: Long) : ProtoValue()
    class Fixed32(val value: Int) : ProtoValue()
    class Bytes(val value: ByteArray) : ProtoValue()
}

/** Decoded message; singular fields follow proto3 last-value-wins, repeated fields keep order. */
internal class ProtoMessage(private val fields: Map<Int, List<ProtoValue>>) {
    fun has(field: Int): Boolean = fields[field]?.isNotEmpty() == true
    fun long(field: Int): Long? = (fields[field]?.lastOrNull() as? ProtoValue.Varint)?.value
    fun int(field: Int): Int? = long(field)?.toInt()
    fun bool(field: Int): Boolean? = long(field)?.let { it != 0L }
    fun double(field: Int): Double? = (fields[field]?.lastOrNull() as? ProtoValue.Fixed64)?.let { Double.fromBits(it.value) }
    fun str(field: Int): String? = (fields[field]?.lastOrNull() as? ProtoValue.Bytes)?.value?.decodeToString()
    fun strings(field: Int): List<String> = fields[field]?.mapNotNull { (it as? ProtoValue.Bytes)?.value?.decodeToString() } ?: emptyList()
    /** Raw wire values in arrival order; diagnostics only. */
    fun values(field: Int): List<ProtoValue> = fields[field] ?: emptyList()
    val templateId: Int get() = int(Rf.TEMPLATE_ID) ?: 0
    val fieldNumbers: Set<Int> get() = fields.keys
}

internal class ProtoDecodeException(message: String) : Exception(message)

internal fun decodeProto(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): ProtoMessage {
    require(start in 0..end && end <= bytes.size)
    val fields = LinkedHashMap<Int, MutableList<ProtoValue>>()
    var offset = start
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (offset >= end) throw ProtoDecodeException("truncated varint")
            val b = bytes[offset++].toInt()
            if (shift < 64) result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 70) throw ProtoDecodeException("varint too long")
        }
    }
    while (offset < end) {
        val key = readVarint()
        val field = (key ushr 3).toInt()
        val wireType = (key and 7).toInt()
        if (field <= 0) throw ProtoDecodeException("invalid field number")
        val value: ProtoValue = when (wireType) {
            0 -> ProtoValue.Varint(readVarint())
            1 -> {
                if (end - offset < 8) throw ProtoDecodeException("truncated fixed64")
                var bits = 0L
                for (i in 0 until 8) bits = bits or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
                offset += 8
                ProtoValue.Fixed64(bits)
            }
            2 -> {
                val length = readVarint()
                if (length < 0 || length > end - offset) throw ProtoDecodeException("truncated bytes")
                val copy = bytes.copyOfRange(offset, offset + length.toInt())
                offset += length.toInt()
                ProtoValue.Bytes(copy)
            }
            5 -> {
                if (end - offset < 4) throw ProtoDecodeException("truncated fixed32")
                var bits = 0
                for (i in 0 until 4) bits = bits or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
                offset += 4
                ProtoValue.Fixed32(bits)
            }
            else -> throw ProtoDecodeException("unsupported wire type $wireType")
        }
        fields.getOrPut(field) { mutableListOf() }.add(value)
    }
    return ProtoMessage(fields)
}

/** Every R | Protocol frame is a big-endian four-byte length followed by one serialized message. */
internal fun frameMessage(payload: ByteArray): ByteArray {
    val out = ByteArray(payload.size + 4)
    val n = payload.size
    out[0] = (n ushr 24).toByte(); out[1] = (n ushr 16).toByte(); out[2] = (n ushr 8).toByte(); out[3] = n.toByte()
    payload.copyInto(out, 4)
    return out
}

/** Accepts several concatenated length-prefixed messages in one WebSocket frame. */
internal fun unframeMessages(frame: ByteArray): List<ProtoMessage> {
    val result = mutableListOf<ProtoMessage>()
    var offset = 0
    while (offset < frame.size) {
        if (frame.size - offset < 4) throw ProtoDecodeException("truncated frame header")
        val length = ((frame[offset].toInt() and 0xFF) shl 24) or ((frame[offset + 1].toInt() and 0xFF) shl 16) or
            ((frame[offset + 2].toInt() and 0xFF) shl 8) or (frame[offset + 3].toInt() and 0xFF)
        offset += 4
        if (length < 0 || length > frame.size - offset) throw ProtoDecodeException("frame length mismatch")
        result.add(decodeProto(frame, offset, offset + length))
        offset += length
    }
    return result
}
