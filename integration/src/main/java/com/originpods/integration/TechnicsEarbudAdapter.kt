package com.originpods.integration

import com.originpods.protocol.technics.TechnicsRaceWireCodec

class TechnicsEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Technics EAH-AZ 系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.technicsAudioConnect)
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = TECHNICS_SPP_UUID,
            id = "technics-airoha-spp",
        ),
        RfcommEndpointSpec.ServiceUuid(
            uuid = STANDARD_SPP_UUID,
            id = "technics-standard-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        return AZ_TWS_NAME.matches(normalizeDeviceName(identity.deviceName.orEmpty()))
    }

    override fun createProtocolSession(): ProtocolSession = TechnicsRaceProtocolSession()

    companion object {
        const val ID = "technics-eah-az"
        const val TECHNICS_SPP_UUID = "00000000-0000-0000-0099-AABBCCDDEEFF"
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private val AZ_TWS_NAME = Regex("^(?:technics)?(?:eah)?az\\d{2,3}[a-z0-9]*$")
    }
}

private class TechnicsRaceProtocolSession : ProtocolSession {
    private val decoder = TechnicsRaceWireCodec.Decoder()
    private var battery = EarbudBattery()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        TechnicsRaceWireCodec.queryAgentBattery,
        TechnicsRaceWireCodec.queryClientBattery,
        TechnicsRaceWireCodec.queryCaseBattery,
        TechnicsRaceWireCodec.queryOutsideControl,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> when (request.mode) {
            NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                TechnicsRaceWireCodec.setNoiseMode(TechnicsRaceWireCodec.NoiseMode.ANC)
            NoiseMode.TRANSPARENCY ->
                TechnicsRaceWireCodec.setNoiseMode(TechnicsRaceWireCodec.NoiseMode.TRANSPARENCY)
            NoiseMode.OFF, NoiseMode.WIND ->
                TechnicsRaceWireCodec.setNoiseMode(TechnicsRaceWireCodec.NoiseMode.OFF)
        }
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is ControlRequest.SetNoiseMode -> listOf(TechnicsRaceWireCodec.queryOutsideControl)
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            TechnicsRaceWireCodec.parseBattery(frame)?.let { reading ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = TechnicsEarbudAdapter().supportedNoiseModes))
                }
                battery = when (reading.component) {
                    TechnicsRaceWireCodec.BatteryComponent.LEFT ->
                        battery.copy(left = BatteryReading(reading.percent, false))
                    TechnicsRaceWireCodec.BatteryComponent.RIGHT ->
                        battery.copy(right = BatteryReading(reading.percent, false))
                    TechnicsRaceWireCodec.BatteryComponent.CASE ->
                        battery.copy(case = BatteryReading(reading.percent, false))
                }
                add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
            }
            TechnicsRaceWireCodec.parseOutsideControl(frame)?.let { state ->
                val mode = when (state.mode) {
                    TechnicsRaceWireCodec.OutsideControlMode.ANC -> NoiseMode.ANC
                    TechnicsRaceWireCodec.OutsideControlMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    TechnicsRaceWireCodec.OutsideControlMode.OFF -> NoiseMode.OFF
                }
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        battery = EarbudBattery()
        handshakePublished = false
    }
}
