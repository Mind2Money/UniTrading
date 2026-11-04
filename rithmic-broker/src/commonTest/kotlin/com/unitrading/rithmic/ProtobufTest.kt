package com.unitrading.rithmic

import kotlin.test.*

class ProtobufTest {
    @Test fun fieldKeysUseProtocVarintEncodingForRithmicFieldNumbers() {
        // template_id = 154467 → key 1235736 → 0x98 0xB6 0x4B, value 10.
        assertContentEquals(byteArrayOf(0x98.toByte(), 0xB6.toByte(), 0x4B, 0x0A), ProtoWriter().int32(Rf.TEMPLATE_ID, 10).toByteArray())
        // user_msg = 132760, wire type 2, "ut1".
        assertContentEquals(byteArrayOf(0xC2.toByte(), 0xE9.toByte(), 0x40, 3, 'u'.code.toByte(), 't'.code.toByte(), '1'.code.toByte()),
            ProtoWriter().string(Rf.USER_MSG, "ut1").toByteArray())
    }

    @Test fun scalarsAndRepeatedStringsRoundTrip() {
        val bytes = ProtoWriter()
            .int32(Rf.TEMPLATE_ID, 312)
            .int32(Rf.SSBOE, -5)
            .uint32(Rf.UPDATE_BITS, 0xFFFF_FFFFL)
            .int64(Rf.BAR_VOLUME, 9_000_000_000L)
            .bool(Rf.IS_SNAPSHOT, true)
            .double(Rf.PRICE, 5499.25)
            .string(Rf.SYMBOL, "MESZ6")
            .string(Rf.ACCOUNT_NAME, "账户 · 测试")
            .strings(Rf.RP_CODE, listOf("7", "no data"))
            .toByteArray()
        val message = decodeProto(bytes)
        assertEquals(312, message.templateId)
        assertEquals(-5, message.int(Rf.SSBOE))
        assertEquals(0xFFFF_FFFFL, message.long(Rf.UPDATE_BITS))
        assertEquals(9_000_000_000L, message.long(Rf.BAR_VOLUME))
        assertEquals(true, message.bool(Rf.IS_SNAPSHOT))
        assertEquals(5499.25, message.double(Rf.PRICE))
        assertEquals("MESZ6", message.str(Rf.SYMBOL))
        assertEquals("账户 · 测试", message.str(Rf.ACCOUNT_NAME))
        assertEquals(listOf("7", "no data"), message.strings(Rf.RP_CODE))
        assertEquals("no data", message.str(Rf.RP_CODE))
        assertNull(message.str(Rf.EXCHANGE))
        assertFalse(message.has(Rf.EXCHANGE))
        assertNull(message.double(Rf.SYMBOL))
        assertNull(message.long(Rf.PRICE))
    }

    @Test fun framingPrefixesBigEndianLengthAndSplitsConcatenatedMessages() {
        val first = ProtoWriter().int32(Rf.TEMPLATE_ID, 19).toByteArray()
        val second = ProtoWriter().int32(Rf.TEMPLATE_ID, 151).string(Rf.SYMBOL, "ESZ6").toByteArray()
        val framed = frameMessage(first)
        assertEquals(first.size + 4, framed.size)
        assertEquals(0, framed[0].toInt()); assertEquals(0, framed[1].toInt()); assertEquals(0, framed[2].toInt()); assertEquals(first.size, framed[3].toInt())
        val decoded = unframeMessages(framed + frameMessage(second))
        assertEquals(listOf(19, 151), decoded.map { it.templateId })
        assertEquals("ESZ6", decoded[1].str(Rf.SYMBOL))
    }

    @Test fun truncatedOrMalformedFramesAreRejectedNotMisread() {
        val payload = ProtoWriter().int32(Rf.TEMPLATE_ID, 19).string(Rf.SYMBOL, "ESZ6").toByteArray()
        val framed = frameMessage(payload)
        assertFailsWith<ProtoDecodeException> { unframeMessages(framed.copyOf(framed.size - 1)) }
        assertFailsWith<ProtoDecodeException> { unframeMessages(byteArrayOf(0, 0, 0)) }
        assertFailsWith<ProtoDecodeException> { decodeProto(byteArrayOf(0x0F)) }   // field 1, wire type 7
        assertFailsWith<ProtoDecodeException> { decodeProto(byteArrayOf(0x08.toByte())) }   // key without value
        assertFailsWith<ProtoDecodeException> { decodeProto(byteArrayOf(0x12, 0x05, 'a'.code.toByte())) }   // length past the end
    }

    @Test fun unknownFieldsAreKeptWithoutBreakingKnownOnes() {
        val bytes = ProtoWriter().int32(1, 1).double(2, 2.5).string(3, "x").int32(Rf.TEMPLATE_ID, 11).toByteArray()
        val message = decodeProto(bytes)
        assertEquals(setOf(1, 2, 3, Rf.TEMPLATE_ID), message.fieldNumbers)
        assertEquals(11, message.templateId)
    }
}
