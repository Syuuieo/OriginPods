package com.originpods.integration

/**
 * Verified Android package identities for vendor headset companion apps.
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

    val huaweiSmartAudio = ControlAppSpec(
        packageName = "com.huawei.smartaudio",
        displayName = "智慧音频",
    )

    val huaweiAiLife = ControlAppSpec(
        packageName = "com.huawei.smarthome",
        displayName = "智慧生活 / AI Life",
    )

    val sonySoundConnect = ControlAppSpec(
        packageName = "com.sony.songpal.mdr",
        displayName = "Sony | Sound Connect",
    )

    val galaxyWearable = ControlAppSpec(
        packageName = "com.samsung.android.app.watchmanager",
        displayName = "Galaxy Wearable",
    )

    val galaxyBuds2ProManager = ControlAppSpec(
        packageName = "com.samsung.accessory.zenithmgr",
        displayName = "Galaxy Buds2 Pro Manager",
    )

    val galaxyBuds2Manager = ControlAppSpec(
        packageName = "com.samsung.accessory.berrymgr",
        displayName = "Galaxy Buds2 Manager",
    )

    val galaxyBudsFeManager = ControlAppSpec(
        packageName = "com.samsung.accessory.pearlmgr",
        displayName = "Galaxy Buds FE Manager",
    )

    val galaxyBuds3Manager = ControlAppSpec(
        packageName = "com.samsung.accessory.jellymgr",
        displayName = "Galaxy Buds3 Manager",
    )

    val galaxyBuds3ProManager = ControlAppSpec(
        packageName = "com.samsung.accessory.paranmgr",
        displayName = "Galaxy Buds3 Pro Manager",
    )

    val galaxyBudsUnified = ControlAppSpec(
        packageName = "com.samsung.accessory.budsunitemgr",
        displayName = "Galaxy Buds",
    )

    val edifierConnect = ControlAppSpec(
        packageName = "com.edifier.edifierconnect",
        displayName = "Edifier Connect",
    )

    val edifierConnex = ControlAppSpec(
        packageName = "com.edifier.edifierconnex",
        displayName = "EDIFIER ConneX",
    )

    val moondrop = ControlAppSpec(
        packageName = "com.moondroplab.moondrop.moondrop_app",
        displayName = "MOONDROP",
    )

    val qcy = ControlAppSpec(
        packageName = "com.qcymall.googleearphonesetup",
        displayName = "QCY",
    )

    val qcyChina = ControlAppSpec(
        packageName = "com.qcymall.earphonesetup",
        displayName = "QCY（中国版）",
    )

    val roseLink = ControlAppSpec(
        packageName = "cn.ikaile.ruoshui.client",
        displayName = "ROSELINK",
    )

    val technicsAudioConnect = ControlAppSpec(
        packageName = "com.panasonic.technicsaudioconnect",
        displayName = "Technics Audio Connect",
    )

    val bose = ControlAppSpec(
        packageName = "com.bose.bosemusic",
        displayName = "Bose",
    )

    val boseChina = ControlAppSpec(
        packageName = "com.bose.bosemusic.china",
        displayName = "Bose（中国版）",
    )

    val boseConnect = ControlAppSpec(
        packageName = "com.bose.monet",
        displayName = "Bose Connect",
    )

    val all: List<ControlAppSpec> = listOf(
        heyMelody,
        oplusWirelessEarphones,
        colorOsWirelessEarphones,
        huaweiSmartAudio,
        huaweiAiLife,
        sonySoundConnect,
        galaxyWearable,
        galaxyBuds2ProManager,
        galaxyBuds2Manager,
        galaxyBudsFeManager,
        galaxyBuds3Manager,
        galaxyBuds3ProManager,
        galaxyBudsUnified,
        edifierConnect,
        edifierConnex,
        moondrop,
        qcy,
        qcyChina,
        roseLink,
        technicsAudioConnect,
        bose,
        boseChina,
        boseConnect,
    )
}
