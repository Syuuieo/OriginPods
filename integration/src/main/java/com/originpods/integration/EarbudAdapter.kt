package com.originpods.integration

abstract class EarbudAdapter {
    abstract val id: String
    abstract val displayName: String
    abstract val resolution: AdapterResolution
    open val controlApps: List<ControlAppSpec> = emptyList()
    open val privateProtocolRequired: Boolean = false
    open val transportReadiness: TransportReadiness = TransportReadiness.SYSTEM_ONLY
    open val supportedNoiseModes: Set<NoiseMode> = emptySet()
    open val transports: List<EarbudTransportSpec> = emptyList()
    open val formFactor: HeadsetFormFactor = HeadsetFormFactor.TWS
    open val featureStateContract: DeviceFeatureStateContract = EmptyDeviceFeatureStateContract
    open val miLinkPresentationId: MiLinkCardPresentationId? = null

    abstract fun matches(identity: EarbudIdentity): Boolean

    open fun createProtocolSession(): ProtocolSession? = null

    open fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    open fun handleBatteryReport(battery: EarbudBattery, scope: AdapterEventScope): FeatureReportDecision =
        FeatureReportDecision.ACCEPT

    open fun handleNoiseModeReport(mode: NoiseMode, scope: AdapterEventScope): FeatureReportDecision =
        FeatureReportDecision.ACCEPT
}

open class StandardEarbudAdapter : EarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "标准蓝牙耳机"
    override val resolution: AdapterResolution = AdapterResolution.STANDARD_FALLBACK

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud

    companion object {
        const val ID = "standard-bluetooth"
    }
}

internal fun normalizeDeviceName(rawName: String): String =
    rawName.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), "")
