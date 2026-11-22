package com.originpods.integration

import com.originpods.protocol.rose.RoseBudsFeelMk2WireCodec
import com.originpods.protocol.rose.RoseEarfreeI5WireCodec

class RoseEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "弱水时砂 Rose Technics 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.roseLink)
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "rose-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("earfree") ||
            name.contains("ceramics") ||
            name.contains("琉璃") ||
            name.contains("budsfeel") ||
            name.contains("rosetechnics") ||
            name.contains("弱水时砂")
    }

    override fun createProtocolSession(): ProtocolSession = RoseEarfreeSession()

    companion object {
        const val ID = "rose-technics"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class RoseEarfreeSession : ProtocolSession {
    private val decoder = RoseEarfreeI5WireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        RoseEarfreeI5WireCodec.queryBattery,
        RoseEarfreeI5WireCodec.queryNoiseMode,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                    RoseEarfreeI5WireCodec.setNoiseMode(RoseEarfreeI5WireCodec.NoiseMode.ANC)
                NoiseMode.TRANSPARENCY ->
                    RoseEarfreeI5WireCodec.setNoiseMode(RoseEarfreeI5WireCodec.NoiseMode.TRANSPARENCY)
                NoiseMode.OFF, NoiseMode.WIND ->
                    RoseEarfreeI5WireCodec.setNoiseMode(RoseEarfreeI5WireCodec.NoiseMode.OFF)
            },
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            RoseEarfreeI5WireCodec.parseBattery(frame)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = RoseEarbudAdapter().supportedNoiseModes))
                }
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(b.leftPercent, false),
                                right = BatteryReading(b.rightPercent, false),
                                case = BatteryReading(b.casePercent, false),
                            ),
                        ),
                    ),
                )
            }
            RoseEarfreeI5WireCodec.parseNoiseMode(frame)?.let { m ->
                val mode = when (m) {
                    RoseEarfreeI5WireCodec.NoiseMode.ANC -> NoiseMode.ANC
                    RoseEarfreeI5WireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    RoseEarfreeI5WireCodec.NoiseMode.WIND -> NoiseMode.WIND
                    RoseEarfreeI5WireCodec.NoiseMode.OFF -> NoiseMode.OFF
                }
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }
}
