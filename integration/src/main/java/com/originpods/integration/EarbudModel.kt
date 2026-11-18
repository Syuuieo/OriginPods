package com.originpods.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class NoiseMode {
    @SerialName("anc")
    ANC,

    @SerialName("anc_adaptive")
    ANC_ADAPTIVE,

    @SerialName("anc_deep")
    ANC_DEEP,

    @SerialName("anc_medium")
    ANC_MEDIUM,

    @SerialName("anc_light")
    ANC_LIGHT,

    @SerialName("off")
    OFF,

    @SerialName("transparency")
    TRANSPARENCY,

    @SerialName("wind")
    WIND,
}

@Serializable
data class BatteryReading(
    val percent: Int?,
    val charging: Boolean,
) {
    init {
        require(percent == null || percent in 0..100)
    }

    val available: Boolean get() = percent != null
}

@Serializable
data class EarbudBattery(
    val left: BatteryReading = BatteryReading(null, false),
    val right: BatteryReading = BatteryReading(null, false),
    val case: BatteryReading = BatteryReading(null, false),
    val overall: BatteryReading = BatteryReading(null, false),
) {
    companion object {
        fun fromAggregate(percent: Int?, charging: Boolean = false): EarbudBattery {
            val reading = BatteryReading(percent?.takeIf { it in 0..100 }, charging = charging)
            return EarbudBattery(
                left = reading,
                right = reading,
                overall = reading,
            )
        }
    }
}

@Serializable
sealed interface DeviceFeatureState {
    @Transient
    val featureId: String
}

@Serializable
@SerialName("standard.battery")
data class BatteryFeatureState(
    val battery: EarbudBattery,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "standard.battery"
    }
}

@Serializable
@SerialName("standard.noise_mode")
data class NoiseModeFeatureState(
    val mode: NoiseMode,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "standard.noise_mode"
    }
}

@Serializable
@SerialName("standard.find_buds")
data class FindBudsFeatureState(
    val ringing: Boolean,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "standard.find_buds"
    }
}

@Serializable
@SerialName("oppo.toggles")
data class OppoTogglesFeatureState(
    val toggles: Map<Int, Boolean>,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "oppo.toggles"
    }
}

@Serializable
@SerialName("oppo.eq_preset")
data class EqPresetFeatureState(
    val presetId: Int,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "oppo.eq_preset"
    }
}

@Serializable
@SerialName("standard.firmware")
data class FirmwareFeatureState(
    val version: String,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "standard.firmware"
    }
}

@Serializable
data class DeviceFeatureSnapshot(
    val values: List<DeviceFeatureState> = emptyList(),
) {
    fun get(featureId: String): DeviceFeatureState? =
        values.firstOrNull { it.featureId == featureId }

    inline fun <reified T : DeviceFeatureState> get(): T? =
        values.filterIsInstance<T>().firstOrNull()

    fun update(next: DeviceFeatureState): DeviceFeatureSnapshot =
        copy(values = values.filterNot { it.featureId == next.featureId } + next)

    fun remove(featureId: String): DeviceFeatureSnapshot =
        copy(values = values.filterNot { it.featureId == featureId })
}

enum class AdapterResolution {
    EXACT_MATCH,
    FAMILY_MATCH,
    STANDARD_FALLBACK,
}

enum class TransportReadiness {
    SYSTEM_ONLY,
    PROTOCOL_HANDSHAKE,
}

data class ControlAppSpec(
    val packageName: String,
    val displayName: String,
)

sealed interface EarbudTransportSpec {
    val id: String
}

sealed interface RfcommEndpointSpec : EarbudTransportSpec {
    data class ServiceUuid(
        val uuid: String,
        override val id: String = "rfcomm-$uuid",
    ) : RfcommEndpointSpec
}

data class EarbudIdentity(
    val address: String,
    val deviceName: String?,
    val standardHeadset: Boolean = true,
    val nativeSystemEarbud: Boolean = false,
)

sealed interface ControlRequest {
    data object Refresh : ControlRequest
    data class SetNoiseMode(val mode: NoiseMode) : ControlRequest
    data class SetFindBuds(val ringing: Boolean) : ControlRequest
    data class SetFeatureToggle(val featureCode: Int, val enabled: Boolean) : ControlRequest
    data class SetEqPreset(val presetId: Int) : ControlRequest
}

sealed interface ProtocolEvent {
    data object HandshakeAccepted : ProtocolEvent
    data class CapabilitiesIdentified(
        val battery: Boolean,
        val noiseModes: Set<NoiseMode> = emptySet(),
    ) : ProtocolEvent
    data class FeatureStateChanged(val state: DeviceFeatureState) : ProtocolEvent
    data class UnknownFrame(
        val version: Int,
        val vendor: Int,
        val command: Int,
        val payloadSize: Int,
    ) : ProtocolEvent
}

interface ProtocolSession {
    fun initialReadCommands(): List<ByteArray>
    fun encode(request: ControlRequest): List<ByteArray>
    fun readback(request: ControlRequest): List<ByteArray>
    fun followUpCommands(event: ProtocolEvent): List<ByteArray>
    fun offer(bytes: ByteArray): List<ProtocolEvent>
    fun reset()
}
