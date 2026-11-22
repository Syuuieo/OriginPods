package com.originpods.integration

import com.originpods.protocol.edifier.EdifierWireCodec

class EdifierEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "漫步者 Edifier 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.edifierConnect,
        ControlAppCatalog.edifierConnex,
    )
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "edifier-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("edifier") || name.contains("lollipods") || name.contains("neobuds") || name.contains("fitbuds")
    }

    override fun createProtocolSession(): ProtocolSession = EdifierProtocolSession()

    companion object {
        const val ID = "edifier-series"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class EdifierProtocolSession : ProtocolSession {
    private val decoder = EdifierWireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        EdifierWireCodec.queryBattery,
        EdifierWireCodec.queryAnc,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                    EdifierWireCodec.setAnc(EdifierWireCodec.ANC_VALUE_DEEP)
                NoiseMode.TRANSPARENCY ->
                    EdifierWireCodec.setAnc(EdifierWireCodec.ANC_VALUE_AMBIENT)
                NoiseMode.WIND ->
                    EdifierWireCodec.setAnc(EdifierWireCodec.ANC_VALUE_WIND)
                NoiseMode.OFF ->
                    EdifierWireCodec.setAnc(EdifierWireCodec.ANC_VALUE_OFF)
            },
        )
        is ControlRequest.SetFeatureToggle -> if (request.featureCode == 1) {
            listOf(EdifierWireCodec.setGameMode(request.enabled))
        } else {
            emptyList()
        }
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            EdifierWireCodec.parseBatteryState(frame)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = EdifierEarbudAdapter().supportedNoiseModes))
                val bat = when (b) {
                    is EdifierWireCodec.BatteryState.Aggregate -> EarbudBattery.fromAggregate(b.percent)
                    is EdifierWireCodec.BatteryState.TwsComponents -> EarbudBattery(
                        left = BatteryReading(b.leftPercent, false),
                        right = BatteryReading(b.rightPercent, false),
                        case = BatteryReading(b.casePercent, false),
                    )
                }
                add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(bat)))
            }
            EdifierWireCodec.parseAncState(frame)?.let { anc ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                val mode = when (anc.mode) {
                    EdifierWireCodec.ANC_VALUE_OFF -> NoiseMode.OFF
                    EdifierWireCodec.ANC_VALUE_AMBIENT -> NoiseMode.TRANSPARENCY
                    EdifierWireCodec.ANC_VALUE_WIND -> NoiseMode.WIND
                    else -> NoiseMode.ANC
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
