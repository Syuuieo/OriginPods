package com.originpods.integration

/**
 * Verified Android package identities for OPPO / OPlus / realme headset companion apps.
 */
object ControlAppCatalog {
    val heyMelody = ControlAppSpec(
        packageName = "com.heytap.headset",
        displayName = "欢律 / HeyMelody",
    )

    val oplusWirelessEarphones = ControlAppSpec(
        packageName = "com.oplus.melody",
        displayName = "无线耳机 / Wireless Earphones",
    )

    val colorOsWirelessEarphones = ControlAppSpec(
        packageName = "com.coloros.oppopods",
        displayName = "无线耳机 (ColorOS)",
    )

    val all: List<ControlAppSpec> = listOf(
        heyMelody,
        oplusWirelessEarphones,
        colorOsWirelessEarphones,
    )
}
