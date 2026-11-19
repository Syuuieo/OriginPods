package com.originpods.integration

open class OnePlusEarbudAdapter : OpoProtocolAdapter() {
    override val id: String = ID
    override val displayName: String = "OnePlus Buds / Buds Pro"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val raw = identity.deviceName.orEmpty()
        val norm = normalizeDeviceName(raw)
        if (FAMILY_NAME.matches(norm) || norm.contains("oneplus") || norm.contains("一加")) {
            return true
        }
        val catalogHit = OppoModelCatalog.byDeviceName(raw) ?: return false
        return catalogHit.brand.equals("OnePlus", ignoreCase = true)
    }

    companion object {
        const val ID = "oneplus-buds-opo-family"
        private val FAMILY_NAME = Regex("(?:oneplus|一加)buds(?:pro(?:[1-9][0-9]?)?|[1-9][0-9]?(?:pro)?)?")
    }
}

class OnePlusBuds4Adapter : OnePlusEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "OnePlus Buds 4"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) && normalizeDeviceName(identity.deviceName.orEmpty()) in EXACT_NAMES

    companion object {
        const val ID = "oneplus-buds-4"
        private val EXACT_NAMES = setOf("oneplusbuds4", "一加buds4")
    }
}
