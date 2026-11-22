package com.originpods.integration

import com.originpods.protocol.honor.HonorX5sSppCodec

class HonorEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "荣耀 Earbuds 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.ANC_DEEP,
        NoiseMode.ANC_MEDIUM,
        NoiseMode.ANC_LIGHT,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "honor-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("honorearbuds") || name.contains("荣耀亲选耳机") || name.contains("荣耀earbuds") || name.contains("x5spro")
    }

    override fun createProtocolSession(): ProtocolSession = HonorProtocolSession()

    companion object {
        const val ID = "honor-earbuds"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class HonorProtocolSession : ProtocolSession {
    private val decoder = HonorX5sSppCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(HonorX5sSppCodec.queryBattery)

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_DEEP, NoiseMode.ANC_ADAPTIVE ->
                    HonorX5sSppCodec.modeCommand(HonorX5sSppCodec.NoiseMode.ANC, HonorX5sSppCodec.AncDepth.DEEP)
                NoiseMode.ANC_MEDIUM ->
                    HonorX5sSppCodec.modeCommand(HonorX5sSppCodec.NoiseMode.ANC, HonorX5sSppCodec.AncDepth.MEDIUM)
                NoiseMode.ANC_LIGHT ->
                    HonorX5sSppCodec.modeCommand(HonorX5sSppCodec.NoiseMode.ANC, HonorX5sSppCodec.AncDepth.LIGHT)
                NoiseMode.TRANSPARENCY ->
                    HonorX5sSppCodec.modeCommand(HonorX5sSppCodec.NoiseMode.TRANSPARENCY)
                NoiseMode.OFF, NoiseMode.WIND ->
                    HonorX5sSppCodec.modeCommand(HonorX5sSppCodec.NoiseMode.OFF)
            },
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (raw in decoder.offer(bytes)) {
            if (HonorX5sSppCodec.isHeartbeat(raw)) continue
            HonorX5sSppCodec.parseBatteryFrame(raw)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = HonorEarbudAdapter().supportedNoiseModes))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(b.leftPercent, false),
                                right = BatteryReading(b.rightPercent, false),
                                case = BatteryReading(b.casePercent, b.caseCharging),
                            ),
                        ),
                    ),
                )
            }
            HonorX5sSppCodec.stateFromFrame(raw)?.let { state ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                val mode = when (state.mode) {
                    HonorX5sSppCodec.NoiseMode.OFF -> NoiseMode.OFF
                    HonorX5sSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    HonorX5sSppCodec.NoiseMode.ANC -> when (state.depth) {
                        HonorX5sSppCodec.AncDepth.DEEP -> NoiseMode.ANC_DEEP
                        HonorX5sSppCodec.AncDepth.MEDIUM -> NoiseMode.ANC_MEDIUM
                        HonorX5sSppCodec.AncDepth.LIGHT -> NoiseMode.ANC_LIGHT
                        HonorX5sSppCodec.AncDepth.SMART -> NoiseMode.ANC_ADAPTIVE
                        null -> NoiseMode.ANC
                    }
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
