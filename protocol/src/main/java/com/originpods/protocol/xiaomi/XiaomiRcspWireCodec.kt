package com.originpods.protocol.xiaomi

/**
 * Xiaomi / Redmi Buds RCSP (FE DC BA ... EF) length-framed wire codec.
 *
 * Reference: CesurPolat/MiBudsClient (rcsp.py, battery_stream.py, noise.py, config_transport.py).
 */
object XiaomiRcspWireCodec {
    const val RFCOMM_CHANNEL = 6
    const val SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"

    private const val HEADER_0 = 0xFE.toByte()
    private const val HEADER_1 = 0xDC.toByte()
    private const val HEADER_2 = 0xBA.toByte()
    private const val TRAILER = 0xEF.toByte()
    private val SUPPORTED_TARGET_ROLES = setOf(0, 4, 7)

    enum class NoiseMode(val code: Int) {
        OFF(0),
        ANC(1),
        TRANSPARENCY(2),
    }

    data class Component(
        val percent: Int?,
        val charging: Boolean,
    )

    sealed interface State {
        data class Battery(
            val left: Component,
            val right: Component,
            val case: Component,
        ) : State

        data class Noise(val mode: NoiseMode) : State
    }

    data class Packet(
        val flags: Int,
        val opcode: Int,
        val payload: ByteArray,
    )

    fun buildBatteryRequest(counter: Int = 0x0B): ByteArray =
        encodeFrame(
            flags = 0xC4,
            opcode = 0x02,
            payload = byteArrayOf((counter and 0xFF).toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )

    fun buildNoiseRead(counter: Int): ByteArray =
        encodeFrame(
            flags = 0xC4,
            opcode = 0xF3,
            payload = byteArrayOf((counter and 0xFF).toByte(), 0x00, 0x0B),
        )

    fun buildNoiseMode(counter: Int, mode: NoiseMode): ByteArray =
        encodeFrame(
            flags = 0xC4,
            opcode = 0x08,
            payload = byteArrayOf((counter and 0xFF).toByte(), 0x02, 0x04, mode.code.toByte()),
        )

    fun encodeFrame(flags: Int, opcode: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val out = ByteArray(8 + len)
        out[0] = HEADER_0
        out[1] = HEADER_1
        out[2] = HEADER_2
        out[3] = (flags and 0xFF).toByte()
        out[4] = (opcode and 0xFF).toByte()
        out[5] = ((len ushr 8) and 0xFF).toByte()
        out[6] = (len and 0xFF).toByte()
        payload.copyInto(out, 7)
        out[7 + len] = TRAILER
        return out
    }

    class Decoder {
        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<State> {
            if (bytes.isEmpty()) return emptyList()
            buffer += bytes
            val states = mutableListOf<State>()
            for (packet in drainPackets()) {
                decodePacket(packet)?.let(states::add)
            }
            return states
        }

        fun reset() {
            buffer = ByteArray(0)
        }

        private fun drainPackets(): List<Packet> {
            val packets = mutableListOf<Packet>()
            while (buffer.isNotEmpty()) {
                val start = findHeader(buffer)
                if (start < 0) {
                    buffer = buffer.takeLast(2).toByteArray()
                    break
                }
                if (start > 0) {
                    buffer = buffer.copyOfRange(start, buffer.size)
                }
                if (buffer.size < 7) break
                val payloadLen = ((buffer[5].toInt() and 0xFF) shl 8) or (buffer[6].toInt() and 0xFF)
                val frameLen = 8 + payloadLen
                if (buffer.size < frameLen) break
                if (buffer[frameLen - 1] != TRAILER) {
                    buffer = buffer.copyOfRange(1, buffer.size)
                    continue
                }
                packets += Packet(
                    flags = buffer[3].toInt() and 0xFF,
                    opcode = buffer[4].toInt() and 0xFF,
                    payload = buffer.copyOfRange(7, frameLen - 1),
                )
                buffer = buffer.copyOfRange(frameLen, buffer.size)
            }
            return packets
        }
    }

    private fun decodePacket(packet: Packet): State? {
        if ((packet.flags and 0x07) !in SUPPORTED_TARGET_ROLES) return null
        val isCommand = (packet.flags and 0x80) != 0
        return when {
            packet.opcode == 0x02 && !isCommand -> {
                if (packet.payload.size < 2 || packet.payload[0] != 0.toByte()) return null
                val fields = parseInfoFields(packet.payload.copyOfRange(2, packet.payload.size)) ?: return null
                parseBatteryBytes(fields[7])
            }
            packet.opcode == 0x0E && isCommand -> {
                if (packet.payload.isEmpty()) return null
                val fields = parseInfoFields(packet.payload.copyOfRange(1, packet.payload.size)) ?: return null
                parseBatteryBytes(fields[0])
            }
            packet.opcode == 0xF3 && !isCommand -> {
                if (packet.payload.size < 2 || packet.payload[0] != 0.toByte()) return null
                val records = parseConfigRecords(packet.payload.copyOfRange(2, packet.payload.size)) ?: return null
                parseNoiseRecord(records[11])
            }
            packet.opcode == 0xF4 -> {
                val offset = if (isCommand) 1 else 2
                if (packet.payload.size < offset || (offset == 2 && packet.payload[0] != 0.toByte())) return null
                val records = parseConfigRecords(packet.payload.copyOfRange(offset, packet.payload.size)) ?: return null
                parseNoiseRecord(records[11])
            }
            else -> null
        }
    }

    private fun parseBatteryBytes(values: ByteArray?): State.Battery? {
        if (values == null || values.size !in 1..3) return null
        val components = Array(3) { Component(null, false) }
        for (i in values.indices) {
            val raw = values[i].toInt() and 0xFF
            if (raw == 0xFF) continue
            val percent = raw and 0x7F
            if (percent > 100) return null
            components[i] = Component(percent = percent, charging = (raw and 0x80) != 0)
        }
        return State.Battery(left = components[0], right = components[1], case = components[2])
    }

    private fun parseNoiseRecord(value: ByteArray?): State.Noise? {
        if (value == null || value.size < 2) return null
        val mode = NoiseMode.entries.firstOrNull { it.code == (value[0].toInt() and 0xFF) } ?: return null
        return State.Noise(mode)
    }

    private fun parseInfoFields(data: ByteArray): Map<Int, ByteArray>? {
        val fields = mutableMapOf<Int, ByteArray>()
        var pos = 0
        while (pos < data.size) {
            val length = data[pos].toInt() and 0xFF
            val end = pos + 1 + length
            if (length < 1 || end > data.size) return null
            val kind = data[pos + 1].toInt() and 0xFF
            if (kind in fields) return null
            fields[kind] = data.copyOfRange(pos + 2, end)
            pos = end
        }
        return fields
    }

    private fun parseConfigRecords(data: ByteArray): Map<Int, ByteArray>? {
        val records = mutableMapOf<Int, ByteArray>()
        var pos = 0
        while (pos < data.size) {
            if (pos + 3 > data.size) return null
            val length = data[pos].toInt() and 0xFF
            val config = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
            val end = if (length == 255) {
                if (config != 0x0034 || pos + 5 > data.size) return null
                pos + 5 + (((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF))
            } else {
                pos + 1 + length
            }
            if (length < 2 || end > data.size || config in records) return null
            records[config] = data.copyOfRange(pos + 3, end)
            pos = end
        }
        return records
    }

    private fun findHeader(data: ByteArray): Int {
        for (i in 0..data.size - 3) {
            if (data[i] == HEADER_0 && data[i + 1] == HEADER_1 && data[i + 2] == HEADER_2) {
                return i
            }
        }
        return -1
    }
}
