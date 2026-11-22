package com.originpods.integration

import com.originpods.protocol.huawei.HuaweiFreebudsSppCodec

class HuaweiEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "华为 FreeBuds 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.huaweiSmartAudio,
        ControlAppCatalog.huaweiAiLife,
    )
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.ANC_ADAPTIVE,
        NoiseMode.ANC_DEEP,
        NoiseMode.ANC_MEDIUM,
        NoiseMode.ANC_LIGHT,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "huawei-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("freebuds") || name.contains("freelace") || name.contains("huaweifree")
    }

    override fun createProtocolSession(): ProtocolSession = HuaweiFreebudsProtocolSession()

    companion object {
        const val ID = "huawei-freebuds"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class HuaweiFreebudsProtocolSession : ProtocolSession {
    private val decoder = HuaweiFreebudsSppCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        HuaweiFreebudsSppCodec.queryBattery,
        HuaweiFreebudsSppCodec.queryNoiseState,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC -> HuaweiFreebudsSppCodec.noiseModeCommand(HuaweiFreebudsSppCodec.NoiseMode.ANC)
                NoiseMode.ANC_ADAPTIVE -> HuaweiFreebudsSppCodec.noiseLevelCommand(HuaweiFreebudsSppCodec.NoiseMode.ANC, 3)
                NoiseMode.ANC_DEEP -> HuaweiFreebudsSppCodec.noiseLevelCommand(HuaweiFreebudsSppCodec.NoiseMode.ANC, 2)
                NoiseMode.ANC_MEDIUM -> HuaweiFreebudsSppCodec.noiseLevelCommand(HuaweiFreebudsSppCodec.NoiseMode.ANC, 0)
                NoiseMode.ANC_LIGHT -> HuaweiFreebudsSppCodec.noiseLevelCommand(HuaweiFreebudsSppCodec.NoiseMode.ANC, 1)
                NoiseMode.TRANSPARENCY -> HuaweiFreebudsSppCodec.noiseModeCommand(HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY)
                NoiseMode.OFF, NoiseMode.WIND -> HuaweiFreebudsSppCodec.noiseModeCommand(HuaweiFreebudsSppCodec.NoiseMode.OFF)
            },
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is ControlRequest.SetNoiseMode -> listOf(HuaweiFreebudsSppCodec.queryNoiseState)
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (raw in decoder.offer(bytes)) {
            HuaweiFreebudsSppCodec.parseBatteryFrame(raw)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = HuaweiEarbudAdapter().supportedNoiseModes))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(b.leftPercent, b.isCharging),
                                right = BatteryReading(b.rightPercent, b.isCharging),
                                case = BatteryReading(b.casePercent, b.isCharging),
                                overall = BatteryReading(b.globalPercent, b.isCharging),
                            ),
                        ),
                    ),
                )
            }
            HuaweiFreebudsSppCodec.parseNoiseState(raw)?.let { n ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                val domainMode = when (n.mode) {
                    HuaweiFreebudsSppCodec.NoiseMode.OFF -> NoiseMode.OFF
                    HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    HuaweiFreebudsSppCodec.NoiseMode.ANC -> when (n.level) {
                        3 -> NoiseMode.ANC_ADAPTIVE
                        2 -> NoiseMode.ANC_DEEP
                        1 -> NoiseMode.ANC_LIGHT
                        0 -> NoiseMode.ANC_MEDIUM
                        else -> NoiseMode.ANC
                    }
                }
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(domainMode)))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }
}
