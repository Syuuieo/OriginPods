package com.originpods.protocol.oppo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OppoWireCodecTest {
    @Test
    fun `decoder handles split chunks and noise`() {
        val packet = OppoWireCodec.setAnc(primary = 0x02)
        val stream = byteArrayOf(0x00, 0xFF.toByte()) + packet
        val decoder = OppoWireCodec.Decoder()

        val first = decoder.offer(stream.copyOfRange(0, 5))
        assertEquals(0, first.size)

        val second = decoder.offer(stream.copyOfRange(5, stream.size))
        assertEquals(1, second.size)
        assertEquals(OppoWireCodec.SET_ANC, second[0].command)
        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x02), second[0].payload)
    }

    @Test
    fun `parses battery response and active report`() {
        val decoder = OppoWireCodec.Decoder()
        val responseBytes = OppoWireCodec.packet(
            command = OppoWireCodec.BATTERY_RESPONSE,
            payload = byteArrayOf(0x01, 0x55, 0x02, 0xD0.toByte(), 0x03, 0x64),
        )
        val frames = decoder.offer(responseBytes)
        val battery = OppoWireCodec.parseBatteryState(frames.single())
        assertNotNull(battery)
        assertEquals(85, battery?.left?.percent)
        assertEquals(false, battery?.left?.charging)
        assertEquals(80, battery?.right?.percent)
        assertEquals(true, battery?.right?.charging)
        assertEquals(100, battery?.case?.percent)
    }
}
