package com.originpods.integration

import com.originpods.protocol.oppo.OppoWireCodec

/** Shared classic OPO protocol behavior. Each brand supplies its own identity and matching rule. */
abstract class OpoProtocolAdapter : StandardEarbudAdapter() {
    abstract override val id: String
    abstract override val displayName: String
    abstract override val resolution: AdapterResolution
    abstract override fun matches(identity: EarbudIdentity): Boolean

    override val controlApps: List<ControlAppSpec> = ControlAppCatalog.all
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    override val supportedNoiseModes: Set<NoiseMode> = emptySet()
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = RFCOMM_UUID,
            id = "oppo-private-rfcomm",
        ),
        RfcommEndpointSpec.ServiceUuid(
            uuid = LEGACY_RFCOMM_UUID,
            id = "oppo-legacy-rfcomm",
        ),
    )
    open val wireConfig: OppoWireConfig = OppoWireConfig.STANDARD

    override fun createProtocolSession(): ProtocolSession =
        OppoProtocolSession(wireConfig)

    companion object {
        const val RFCOMM_UUID = "0000079a-d102-11e1-9b23-00025b00a5a5"
        const val LEGACY_RFCOMM_UUID = "00001107-d102-11e1-9b23-00025b00a5a5"
    }
}

/** OPPO/Enco/realme names select a candidate; valid protocol reports unlock private capabilities. */
open class OppoEarbudAdapter : OpoProtocolAdapter() {
    override val id: String = ID
    override val displayName: String = "OPPO Enco / realme 全系"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val rawName = identity.deviceName.orEmpty()
        val name = normalizeDeviceName(rawName)
        if (name.contains("oppo") || name.contains("enco") || name.contains("realme")) {
            return true
        }
        val catalogHit = OppoModelCatalog.byDeviceName(rawName) ?: return false
        return catalogHit.brand.equals("oppo", ignoreCase = true) ||
            catalogHit.brand.equals("realme", ignoreCase = true)
    }

    companion object {
        const val ID = "oppo-enco-family"
    }
}

abstract class OppoModelEarbudAdapter : OppoEarbudAdapter() {
    final override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
}

/** OPPO Enco Air2 Pro reverses the family's standard ANC and off values. */
class OppoEncoAir2ProAdapter : OppoModelEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "OPPO Enco Air2 Pro"
    override val wireConfig: OppoWireConfig = OppoWireConfig.COMPATIBLE

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encoair2pro")

    companion object {
        const val ID = "oppo-enco-air2-pro"
    }
}

class OppoEncoFree4Adapter : OppoModelEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "OPPO Enco Free4"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encofree4")

    companion object {
        const val ID = "oppo-enco-free4"
    }
}

class OppoEncoX3Adapter : OppoModelEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "OPPO Enco X3"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encox3")

    companion object {
        const val ID = "oppo-enco-x3"
    }
}

class OppoEncoAir5Adapter : OppoModelEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "OPPO Enco Air5"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encoair5")

    companion object {
        const val ID = "oppo-enco-air5"
    }
}

data class OppoWireConfig(
    val ancPrimary: Int,
    val offPrimary: Int,
) {
    companion object {
        val STANDARD = OppoWireConfig(ancPrimary = 0x02, offPrimary = 0x01)
        val COMPATIBLE = OppoWireConfig(ancPrimary = 0x01, offPrimary = 0x02)
    }
}

private class OppoProtocolSession(
    private val configuration: OppoWireConfig,
) : ProtocolSession {
    private val decoder = OppoWireCodec.Decoder()
    private var handshakePublished = false
    private var battery = EarbudBattery()
    private var pendingNotificationRegistration: ByteArray? = null
    private var activeModel: OppoModelSpec? = null

    override fun initialReadCommands(): List<ByteArray> = listOf(
        OppoWireCodec.queryProductId,
        OppoWireCodec.queryNotificationSupport,
        OppoWireCodec.queryBattery,
        OppoWireCodec.queryAnc,
        OppoWireCodec.queryStatus,
        OppoWireCodec.queryEq,
        OppoWireCodec.queryFirmware,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> initialReadCommands()
        is ControlRequest.SetNoiseMode -> {
            val dynamicBit = activeModel?.modeToBit?.get(request.mode)
            val bytes = if (dynamicBit != null) {
                OppoWireCodec.setAncByIndex(dynamicBit)
            } else {
                request.mode.toWireCommand()
            }
            bytes?.let(::listOf).orEmpty()
        }
        is ControlRequest.SetFindBuds -> listOf(OppoWireCodec.setFindBuds(request.ringing))
        is ControlRequest.SetFeatureToggle -> listOf(
            OppoWireCodec.setFeature(request.featureCode, request.enabled),
        )
        is ControlRequest.SetEqPreset -> listOf(OppoWireCodec.setEqPreset(request.presetId))
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        ControlRequest.Refresh -> emptyList()
        is ControlRequest.SetNoiseMode -> listOf(OppoWireCodec.queryAnc)
        is ControlRequest.SetFindBuds -> emptyList()
        is ControlRequest.SetFeatureToggle -> listOf(OppoWireCodec.queryStatus)
        is ControlRequest.SetEqPreset -> listOf(OppoWireCodec.queryEq)
    }

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> {
        if (event !== ProtocolEvent.HandshakeAccepted) return emptyList()
        return pendingNotificationRegistration
            ?.also { pendingNotificationRegistration = null }
            ?.let(::listOf)
            .orEmpty()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> =
        decoder.offer(bytes).flatMap { frame ->
            buildList {
                OppoWireCodec.parseProductId(frame)?.let { productId ->
                    val model = OppoModelCatalog.byProductId(productId)
                    if (model != null) {
                        activeModel = model
                        publishHandshakeIfNeeded()
                        if (model.supportedNoiseModes.isNotEmpty()) {
                            add(
                                ProtocolEvent.CapabilitiesIdentified(
                                    battery = false,
                                    noiseModes = model.supportedNoiseModes,
                                ),
                            )
                        }
                    }
                    return@buildList
                }
                OppoWireCodec.parseNotificationSupport(frame)?.let { advertisedIds ->
                    val subscribableIds = advertisedIds.filterNot { it.isDebugNotification() }
                        .toByteArray()
                    pendingNotificationRegistration = subscribableIds
                        .takeIf(ByteArray::isNotEmpty)
                        ?.let(OppoWireCodec::registerNotifications)
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                    return@buildList
                }
                OppoWireCodec.parseBatteryState(frame)?.let { state ->
                    publishHandshakeIfNeeded()
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    battery = battery.copy(
                        left = state.left?.toDomainReading() ?: battery.left,
                        right = state.right?.toDomainReading() ?: battery.right,
                        case = state.case?.toDomainReading() ?: battery.case,
                    )
                    add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
                    return@buildList
                }
                val dynamicMode = OppoWireCodec.parseAncBitIndex(frame)
                    ?.let { bit -> activeModel?.bitToMode?.get(bit) }
                val resolvedMode = dynamicMode ?: OppoWireCodec.parseAncState(frame)?.toDomainMode()
                if (resolvedMode != null) {
                    publishHandshakeIfNeeded()
                    val capsModes = activeModel?.supportedNoiseModes?.takeIf { it.isNotEmpty() }
                        ?: OPPO_NOISE_MODES
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = capsModes,
                        ),
                    )
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(resolvedMode)))
                    return@buildList
                }
                OppoWireCodec.parseStatusMap(frame)?.let { toggles ->
                    publishHandshakeIfNeeded()
                    add(ProtocolEvent.FeatureStateChanged(OppoTogglesFeatureState(toggles)))
                    return@buildList
                }
                OppoWireCodec.parseEqPreset(frame)?.let { presetId ->
                    publishHandshakeIfNeeded()
                    add(ProtocolEvent.FeatureStateChanged(EqPresetFeatureState(presetId)))
                    return@buildList
                }
                OppoWireCodec.parseFirmware(frame)?.let { version ->
                    publishHandshakeIfNeeded()
                    add(ProtocolEvent.FeatureStateChanged(FirmwareFeatureState(version)))
                    return@buildList
                }
                add(
                    ProtocolEvent.UnknownFrame(
                        version = 1,
                        vendor = OPPO_LOG_VENDOR,
                        command = frame.command,
                        payloadSize = frame.payload.size,
                    ),
                )
            }
        }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        battery = EarbudBattery()
        pendingNotificationRegistration = null
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun NoiseMode.toWireCommand(): ByteArray? = when (this) {
        NoiseMode.ANC -> OppoWireCodec.setAnc(configuration.ancPrimary)
        NoiseMode.ANC_ADAPTIVE -> OppoWireCodec.setAnc(primary = 0x00, secondary = 0x08)
        NoiseMode.ANC_DEEP -> OppoWireCodec.setAnc(primary = 0x10)
        NoiseMode.ANC_MEDIUM -> OppoWireCodec.setAnc(primary = 0x20)
        NoiseMode.ANC_LIGHT -> OppoWireCodec.setAnc(primary = 0x40)
        NoiseMode.OFF -> OppoWireCodec.setAnc(configuration.offPrimary)
        NoiseMode.TRANSPARENCY -> OppoWireCodec.setAnc(primary = 0x04)
        NoiseMode.WIND -> null
    }

    private fun OppoWireCodec.AncState.toDomainMode(): NoiseMode? = when {
        primary == configuration.ancPrimary && secondary in setOf(null, 0x00) -> NoiseMode.ANC
        primary == configuration.offPrimary && secondary in setOf(null, 0x00) -> NoiseMode.OFF
        primary == 0x04 && secondary in setOf(null, 0x00) -> NoiseMode.TRANSPARENCY
        primary == 0x00 && secondary in setOf(0x01, 0x02) -> NoiseMode.TRANSPARENCY
        primary == 0x00 && secondary == 0x08 -> NoiseMode.ANC_ADAPTIVE
        primary == 0x08 && secondary in setOf(null, 0x00) -> NoiseMode.OFF
        primary == 0x10 && secondary in setOf(null, 0x00) -> NoiseMode.ANC_DEEP
        primary == 0x20 && secondary in setOf(null, 0x00) -> NoiseMode.ANC_MEDIUM
        primary == 0x40 && secondary in setOf(null, 0x00) -> NoiseMode.ANC_LIGHT
        primary == 0x80 && secondary in setOf(null, 0x00) -> NoiseMode.ANC
        else -> null
    }

    private fun OppoWireCodec.BatteryReading.toDomainReading(): BatteryReading =
        BatteryReading(percent = percent, charging = charging)

    private fun Byte.isDebugNotification(): Boolean =
        (toInt() and 0xF0) == 0xF0

    private companion object {
        const val OPPO_LOG_VENDOR = 0x4F50
        val OPPO_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.ANC_ADAPTIVE,
            NoiseMode.ANC_DEEP,
            NoiseMode.ANC_MEDIUM,
            NoiseMode.ANC_LIGHT,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )
    }
}
