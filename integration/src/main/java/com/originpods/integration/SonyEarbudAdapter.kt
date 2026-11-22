package com.originpods.integration

import com.originpods.protocol.sony.SonyHeadphonesWireCodec

class SonyEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Sony 耳机系列 (WF / WH / LinkBuds)"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.sonySoundConnect)
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SonyHeadphonesWireCodec.RFCOMM_SERVICE_V2,
            id = "sony-v2",
        ),
        RfcommEndpointSpec.ServiceUuid(
            uuid = SonyHeadphonesWireCodec.RFCOMM_SERVICE_V1,
            id = "sony-v1",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("wf1000x") ||
            name.contains("wh1000x") ||
            name.contains("linkbuds") ||
            name.contains("wfc700") ||
            name.contains("wfc500") ||
            name.contains("whch") ||
            name.contains("ultwear") ||
            name.startsWith("sony")
    }

    override fun createProtocolSession(): ProtocolSession = SonyHeadphonesProtocolSession()

    companion object {
        const val ID = "sony-headphones"
    }
}

private class SonyHeadphonesProtocolSession : ProtocolSession {
    private val decoder = SonyHeadphonesWireCodec.Decoder()
    private var seq = 0
    private var handshakePublished = false

    private fun nextSeq(): Int = (seq++ and 0x01)

    override fun initialReadCommands(): List<ByteArray> = listOf(
        // INIT_GET (0x00, 0x00)
        SonyHeadphonesWireCodec.encode(
            type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
            sequence = nextSeq(),
            payload = byteArrayOf(0x00, 0x00),
        ),
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> listOf(
            // BATTERY_GET_DUAL (0x10, 0x01) + NCASM_GET (0x66, 0x02)
            SonyHeadphonesWireCodec.encode(
                type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
                sequence = nextSeq(),
                payload = byteArrayOf(0x10, 0x01),
            ),
            SonyHeadphonesWireCodec.encode(
                type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
                sequence = nextSeq(),
                payload = byteArrayOf(0x66, 0x02),
            ),
        )
        is ControlRequest.SetNoiseMode -> {
            val (ncAsmEnabled, asmMode, level) = when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                    Triple(0x11.toByte(), 0x00.toByte(), 0x00.toByte())
                NoiseMode.TRANSPARENCY ->
                    Triple(0x11.toByte(), 0x01.toByte(), 0x14.toByte())
                NoiseMode.OFF, NoiseMode.WIND ->
                    Triple(0x10.toByte(), 0x00.toByte(), 0x00.toByte())
            }
            listOf(
                SonyHeadphonesWireCodec.encode(
                    type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
                    sequence = nextSeq(),
                    payload = byteArrayOf(0x68, 0x02, ncAsmEnabled, 0x01, asmMode, 0x00, level),
                ),
            )
        }
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> {
        if (event is ProtocolEvent.HandshakeAccepted) {
            return encode(ControlRequest.Refresh)
        }
        return emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            if (!handshakePublished && frame.payload.isNotEmpty()) {
                handshakePublished = true
                add(ProtocolEvent.HandshakeAccepted)
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = true,
                        noiseModes = SonyEarbudAdapter().supportedNoiseModes,
                    ),
                )
            }
            val p = frame.payload
            if (p.size >= 4 && p[0] == 0x11.toByte()) {
                val sub = p[1].toInt() and 0xFF
                when (sub) {
                    0x01 -> {
                        val left = (p[2].toInt() and 0xFF).takeIf { it in 0..100 }
                        val right = if (p.size >= 6) (p[4].toInt() and 0xFF).takeIf { it in 0..100 } else null
                        val box = if (p.size >= 8) (p[6].toInt() and 0xFF).takeIf { it in 0..100 } else null
                        add(
                            ProtocolEvent.FeatureStateChanged(
                                BatteryFeatureState(
                                    EarbudBattery(
                                        left = BatteryReading(left, false),
                                        right = BatteryReading(right, false),
                                        case = BatteryReading(box, false),
                                    ),
                                ),
                            ),
                        )
                    }
                    0x00 -> {
                        val level = (p[2].toInt() and 0xFF).takeIf { it in 0..100 }
                        add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(EarbudBattery.fromAggregate(level))))
                    }
                }
            } else if (p.size >= 5 && (p[0] == 0x67.toByte() || p[0] == 0x69.toByte())) {
                val active = (p[2].toInt() and 0xFF) == 0x11
                val asm = (p.getOrNull(4)?.toInt() ?: 0) and 0xFF
                val mode = if (!active) NoiseMode.OFF else if (asm != 0) NoiseMode.TRANSPARENCY else NoiseMode.ANC
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        seq = 0
        handshakePublished = false
    }
}
