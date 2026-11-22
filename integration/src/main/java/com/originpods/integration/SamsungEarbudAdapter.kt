package com.originpods.integration

import com.originpods.protocol.samsung.SamsungBudsCodec

class SamsungEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "三星 Galaxy Buds 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.galaxyWearable,
        ControlAppCatalog.galaxyBudsUnified,
        ControlAppCatalog.galaxyBuds3ProManager,
        ControlAppCatalog.galaxyBuds3Manager,
        ControlAppCatalog.galaxyBuds2ProManager,
        ControlAppCatalog.galaxyBuds2Manager,
        ControlAppCatalog.galaxyBudsFeManager,
    )
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "samsung-spp",
        ),
        RfcommEndpointSpec.ServiceUuid(
            uuid = LEGACY_SPP_UUID,
            id = "samsung-legacy-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("galaxybuds")
    }

    override fun createProtocolSession(): ProtocolSession = SamsungBudsProtocolSession()

    companion object {
        const val ID = "samsung-galaxy-buds"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        const val LEGACY_SPP_UUID = "2E73A4AD-332D-41FC-90E2-16BEF06523F2"
    }
}

private class SamsungBudsProtocolSession : ProtocolSession {
    private val decoder = SamsungBudsCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        SamsungBudsCodec.managerInfoCommand(),
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                    SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.ANC)
                NoiseMode.TRANSPARENCY ->
                    SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.AMBIENT)
                NoiseMode.OFF, NoiseMode.WIND ->
                    SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.OFF)
            },
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (raw in decoder.offer(bytes)) {
            val frame = SamsungBudsCodec.parseFrame(raw) ?: continue
            SamsungBudsCodec.parseExtendedStatus(frame)?.let { ext ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = SamsungEarbudAdapter().supportedNoiseModes))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(ext.battery.left, ext.battery.leftCharging),
                                right = BatteryReading(ext.battery.right, ext.battery.rightCharging),
                                case = BatteryReading(ext.battery.case, ext.battery.caseCharging),
                            ),
                        ),
                    ),
                )
                ext.noiseMode?.let { add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(it.toDomain()))) }
            } ?: SamsungBudsCodec.parseBattery(frame)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(b.left, b.leftCharging),
                                right = BatteryReading(b.right, b.rightCharging),
                                case = BatteryReading(b.case, b.caseCharging),
                            ),
                        ),
                    ),
                )
            } ?: SamsungBudsCodec.parseNoiseMode(frame)?.let { mode ->
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode.toDomain())))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }

    private fun SamsungBudsCodec.NoiseMode.toDomain(): NoiseMode = when (this) {
        SamsungBudsCodec.NoiseMode.OFF -> NoiseMode.OFF
        SamsungBudsCodec.NoiseMode.ANC -> NoiseMode.ANC
        SamsungBudsCodec.NoiseMode.AMBIENT -> NoiseMode.TRANSPARENCY
    }
}
