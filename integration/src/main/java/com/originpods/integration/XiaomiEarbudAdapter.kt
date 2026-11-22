package com.originpods.integration

import com.originpods.protocol.xiaomi.XiaomiRcspWireCodec

/** Xiaomi / Redmi Buds RCSP adapter (RFCOMM channel 6 / SPP). */
class XiaomiEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "小米 / Redmi Buds 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(
            number = XiaomiRcspWireCodec.RFCOMM_CHANNEL,
            secure = true,
            id = "xiaomi-rcsp-ch6",
        ),
        RfcommEndpointSpec.ServiceUuid(
            uuid = XiaomiRcspWireCodec.SPP_UUID,
            id = "xiaomi-rcsp-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("redmibuds") ||
            name.contains("redmiairdots") ||
            name.contains("xiaomibuds") ||
            name.contains("mibuds") ||
            name.contains("mitruewireless") ||
            name.contains("miheadphones") ||
            name.startsWith("xiaomi") ||
            name.startsWith("redmi") ||
            name.startsWith("pocopods")
    }

    override fun createProtocolSession(): ProtocolSession = XiaomiRcspProtocolSession()

    companion object {
        const val ID = "xiaomi-redmi-buds"
    }
}

private class XiaomiRcspProtocolSession : ProtocolSession {
    private val decoder = XiaomiRcspWireCodec.Decoder()
    private var counter = 0xA0
    private var handshakePublished = false

    private fun nextCounter(): Int {
        counter = (counter + 1) and 0xFF
        if (counter == 0x90) counter = 0x91
        return counter
    }

    override fun initialReadCommands(): List<ByteArray> = listOf(
        XiaomiRcspWireCodec.buildBatteryRequest(nextCounter()),
        XiaomiRcspWireCodec.buildNoiseRead(nextCounter()),
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            XiaomiRcspWireCodec.buildNoiseMode(nextCounter(), request.mode.toWireMode()),
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (state in decoder.offer(bytes)) {
            if (!handshakePublished) {
                handshakePublished = true
                add(ProtocolEvent.HandshakeAccepted)
            }
            when (state) {
                is XiaomiRcspWireCodec.State.Battery -> {
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            BatteryFeatureState(
                                EarbudBattery(
                                    left = BatteryReading(state.left.percent, state.left.charging),
                                    right = BatteryReading(state.right.percent, state.right.charging),
                                    case = BatteryReading(state.case.percent, state.case.charging),
                                ),
                            ),
                        ),
                    )
                }
                is XiaomiRcspWireCodec.State.Noise -> {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                        ),
                    )
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(state.mode.toDomainMode())))
                }
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }

    private fun NoiseMode.toWireMode(): XiaomiRcspWireCodec.NoiseMode = when (this) {
        NoiseMode.OFF -> XiaomiRcspWireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> XiaomiRcspWireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.ANC,
        NoiseMode.ANC_ADAPTIVE,
        NoiseMode.ANC_DEEP,
        NoiseMode.ANC_MEDIUM,
        NoiseMode.ANC_LIGHT,
        NoiseMode.WIND,
        -> XiaomiRcspWireCodec.NoiseMode.ANC
    }

    private fun XiaomiRcspWireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        XiaomiRcspWireCodec.NoiseMode.OFF -> NoiseMode.OFF
        XiaomiRcspWireCodec.NoiseMode.ANC -> NoiseMode.ANC
        XiaomiRcspWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }
}
