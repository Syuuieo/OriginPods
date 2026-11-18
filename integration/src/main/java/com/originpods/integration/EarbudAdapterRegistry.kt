package com.originpods.integration

/**
 * Resolves the highest-specificity [EarbudAdapter] for a physical Bluetooth headset,
 * skipping user-disabled adapter IDs so disabling an exact model falls back to its family,
 * and disabling the family falls back to [StandardEarbudAdapter].
 */
object EarbudAdapterRegistry {
    private val factories: List<() -> EarbudAdapter> = listOf(
        ::OppoEncoAir2ProAdapter,
        ::OppoEncoFree4Adapter,
        ::OppoEncoX3Adapter,
        ::OppoEncoAir5Adapter,
        ::OnePlusBuds4Adapter,
        ::OppoEarbudAdapter,
        ::OnePlusEarbudAdapter,
        ::StandardEarbudAdapter,
    )

    fun catalog(): List<EarbudAdapter> = factories.map { it() }

    fun resolve(
        identity: EarbudIdentity,
        disabledAdapterIds: Set<String> = emptySet(),
    ): EarbudAdapter? {
        for (factory in factories) {
            val adapter = factory()
            if (adapter.id in disabledAdapterIds) continue
            if (adapter.matches(identity)) return adapter
        }
        return null
    }
}
