package com.originpods.integration

import com.originpods.protocol.moondrop.MoondropPuddingWireCodec
import com.originpods.protocol.moondrop.MoondropRobinWireCodec
import com.originpods.protocol.moondrop.MoondropZzzAngelsWireCodec

open class MoondropEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "水月雨 MOONDROP 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.moondrop)
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "moondrop-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("moondrop") ||
            name.contains("水月雨") ||
            name.contains("robin") ||
            name.contains("知更鸟") ||
            name.contains("pudding") ||
            name.contains("梦想碟") ||
            name.contains("梦回") ||
            name.contains("spacetravel")
    }

    override fun createProtocolSession(): ProtocolSession = MoondropPuddingSession()

    companion object {
        const val ID = "moondrop-series"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class MoondropPuddingSession : ProtocolSession {
    private val decoder = MoondropPuddingWireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        MoondropPuddingWireCodec.queryBattery,
        MoondropPuddingWireCodec.queryNoiseMode,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                    MoondropPuddingWireCodec.setNoiseMode(MoondropPuddingWireCodec.NoiseMode.BASIC)
                NoiseMode.TRANSPARENCY ->
                    MoondropPuddingWireCodec.setNoiseMode(MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY)
                NoiseMode.WIND ->
                    MoondropPuddingWireCodec.setNoiseMode(MoondropPuddingWireCodec.NoiseMode.WIND)
                NoiseMode.OFF ->
                    MoondropPuddingWireCodec.setNoiseMode(MoondropPuddingWireCodec.NoiseMode.OFF)
            },
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            if (MoondropPuddingWireCodec.parseHandshake(frame) && !handshakePublished) {
                handshakePublished = true
                add(ProtocolEvent.HandshakeAccepted)
            }
            MoondropPuddingWireCodec.parseBattery(frame)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = MoondropEarbudAdapter().supportedNoiseModes))
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
            (MoondropPuddingWireCodec.parseNoiseModeQuery(frame) ?: MoondropPuddingWireCodec.parseNoiseModeConfirm(frame))?.let { m ->
                val mode = when (m) {
                    MoondropPuddingWireCodec.NoiseMode.OFF -> NoiseMode.OFF
                    MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    MoondropPuddingWireCodec.NoiseMode.ADAPTIVE -> NoiseMode.ANC_ADAPTIVE
                    MoondropPuddingWireCodec.NoiseMode.WIND -> NoiseMode.WIND
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
