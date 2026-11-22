package com.originpods.integration

import com.originpods.protocol.qcy.QcyWireCodec

class QcyEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "QCY 耳机系列"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.qcy,
        ControlAppCatalog.qcyChina,
    )
    override val supportedNoiseModes: Set<NoiseMode> = setOf(
        NoiseMode.ANC,
        NoiseMode.OFF,
        NoiseMode.TRANSPARENCY,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "qcy-spp",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("qcy") || name.contains("melobuds")
    }

    override fun createProtocolSession(): ProtocolSession = QcyProtocolSession()

    companion object {
        const val ID = "qcy-series"
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    }
}

private class QcyProtocolSession : ProtocolSession {
    private val decoder = QcyWireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        QcyWireCodec.queryBattery,
        QcyWireCodec.queryNoiseMode,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> listOf(
            when (request.mode) {
                NoiseMode.ANC, NoiseMode.ANC_ADAPTIVE, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT ->
                    QcyWireCodec.setNoiseMode(QcyWireCodec.NoiseMode.ANC)
                NoiseMode.TRANSPARENCY ->
                    QcyWireCodec.setNoiseMode(QcyWireCodec.NoiseMode.TRANSPARENCY)
                NoiseMode.OFF, NoiseMode.WIND ->
                    QcyWireCodec.setNoiseMode(QcyWireCodec.NoiseMode.OFF)
            },
        )
        is ControlRequest.SetFindBuds,
        is ControlRequest.SetFeatureToggle,
        is ControlRequest.SetEqPreset,
        -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            QcyWireCodec.parseBattery(frame)?.let { b ->
                if (!handshakePublished) {
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                }
                add(ProtocolEvent.CapabilitiesIdentified(battery = true, noiseModes = QcyEarbudAdapter().supportedNoiseModes))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(b.left.percent, b.left.charging),
                                right = BatteryReading(b.right.percent, b.right.charging),
                                case = BatteryReading(b.case.percent, b.case.charging),
                            ),
                        ),
                    ),
                )
            }
            QcyWireCodec.parseNoiseMode(frame)?.let { m ->
                val mode = when (m) {
                    QcyWireCodec.NoiseMode.ANC, QcyWireCodec.NoiseMode.OUTDOOR -> NoiseMode.ANC
                    QcyWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    QcyWireCodec.NoiseMode.OFF -> NoiseMode.OFF
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
