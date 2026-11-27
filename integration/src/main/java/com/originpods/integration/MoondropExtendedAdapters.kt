package com.originpods.integration

import com.originpods.protocol.moondrop.MoondropZzzAngelsWireCodec

/** MOONDROP MIRAGE (shares Pudding FF/Jieli SPP protocol). */
class MoondropMirageAdapter : MoondropEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "MOONDROP MIRAGE"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return ("moondrop" in name || "水月雨" in name) && "mirage" in name
    }

    companion object {
        const val ID = "moondrop-mirage"
    }
}

/** MOONDROP PILL (水月雨「药丸」耳夹款, GAIA SPP battery telemetry). */
class MoondropPillAdapter : MoondropEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "MOONDROP PILL"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val supportedNoiseModes: Set<NoiseMode> = emptySet()

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name == "moondroppill"
    }

    override fun createProtocolSession(): ProtocolSession = MoondropGaiaBatterySession()

    companion object {
        const val ID = "moondrop-pill"
    }
}

/** MOONDROP ZZZ-ANGELS-OWS (水月雨 × 绝区零「妄想天使·闪亮登场」耳夹款). */
class MoondropZzzAngelsAdapter : MoondropEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "MOONDROP ZZZ-ANGELS-OWS"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val supportedNoiseModes: Set<NoiseMode> = emptySet()

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        if (name.endsWith("lea")) return false
        return name == "zzzangelsows" || name == "moondropzzzangelsows"
    }

    override fun createProtocolSession(): ProtocolSession = MoondropGaiaBatterySession()

    companion object {
        const val ID = "moondrop-zzz-angels-ows"
    }
}

private class MoondropGaiaBatterySession : ProtocolSession {
    private val decoder = MoondropZzzAngelsWireCodec.Decoder()
    private var handshakeAccepted = false
    private var battery = EarbudBattery()

    override fun initialReadCommands(): List<ByteArray> =
        listOf(MoondropZzzAngelsWireCodec.handshake)

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> =
        if (event === ProtocolEvent.HandshakeAccepted) listOf(MoondropZzzAngelsWireCodec.queryBattery)
        else emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> listOf(MoondropZzzAngelsWireCodec.queryBattery)
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (!handshakeAccepted) {
                if (MoondropZzzAngelsWireCodec.parseHandshake(frame)) {
                    handshakeAccepted = true
                    add(ProtocolEvent.HandshakeAccepted)
                } else if (MoondropZzzAngelsWireCodec.isHandshakeFailure(frame)) {
                    add(ProtocolEvent.HandshakeRejected)
                }
                return@forEach
            }

            MoondropZzzAngelsWireCodec.parseBattery(frame)?.let { report ->
                battery = battery.copy(
                    left = report.left?.let { BatteryReading(it.percent, charging = false) } ?: battery.left,
                    right = report.right?.let { BatteryReading(it.percent, charging = false) } ?: battery.right,
                    case = report.chargingCase?.let { BatteryReading(it.percent, charging = false) } ?: battery.case,
                )
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakeAccepted = false
        battery = EarbudBattery()
    }
}
