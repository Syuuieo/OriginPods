package com.originpods.integration

import com.originpods.protocol.bose.BoseBmapWireCodec
import com.originpods.protocol.bose.BoseProductCatalog

class BoseEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Bose QuietComfort 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.bose,
        ControlAppCatalog.boseChina,
        ControlAppCatalog.boseConnect,
    )
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(number = 8),
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "bose-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("bose") ||
            name.contains("quietcomfort") ||
            name.contains("qcearbuds") ||
            name.contains("qc45") ||
            name.contains("nc700")
    }

    override fun createProtocolSession(): ProtocolSession = BoseProtocolSession()

    companion object {
        const val ID = "bose-bmap"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class BoseProtocolSession : ProtocolSession {
    private val decoder = BoseBmapWireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        BoseBmapWireCodec.queryProductIdentity,
        BoseBmapWireCodec.queryBattery,
        BoseBmapWireCodec.queryCurrentMode,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> listOf(
            BoseBmapWireCodec.queryBattery,
            BoseBmapWireCodec.queryCurrentMode,
        )
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                    BoseBmapWireCodec.setAnr(1)
                NoiseMode.TRANSPARENCY ->
                    BoseBmapWireCodec.setCnc(0, true)
                NoiseMode.OFF, NoiseMode.WIND ->
                    BoseBmapWireCodec.setAnr(0)
            },
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            BoseBmapWireCodec.parseProductIdentity(frame)?.let { pid ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = BoseEarbudAdapter().supportedNoiseModes))
                }
                BoseProductCatalog.find(pid.productId)?.let {
                    add(ProtocolEvent.ProductIdentified(pid.productId))
                }
            }
            BoseBmapWireCodec.parseBatteryState(frame)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                val bat = EarbudBattery(
                    left = BatteryReading(b.leftPercent, false),
                    right = BatteryReading(b.rightPercent, false),
                    case = BatteryReading(b.casePercent, false),
                    overall = BatteryReading(b.overallPercent, false),
                )
                add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(bat)))
            }
            BoseBmapWireCodec.parseAnrState(frame)?.let { anr ->
                val mode = if (anr.level > 0) NoiseMode.ANC else NoiseMode.OFF
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }
}
