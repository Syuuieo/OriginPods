package com.originpods.integration

data class ThirdPartyModelSpec(
    val brand: String,
    val brandKey: String,
    val name: String,
    val adapterId: String,
    val ancSupported: Boolean,
)

/**
 * Concrete supported device catalog for non-OPPO third-party protocol adapters.
 * Every entry here matches its target [EarbudAdapter] in [EarbudAdapterRegistry].
 */
object MultiBrandModelCatalog {
    val models: List<ThirdPartyModelSpec> = listOf(
        // 小米 / Redmi (RCSP)
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi Buds 3", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi Buds 3T Pro", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi Buds 4 Pro", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi Buds 5", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi Buds 5 Pro", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi Buds 6", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi OpenWear Stereo", XiaomiEarbudAdapter.ID, false),
        ThirdPartyModelSpec("小米", "xiaomi", "Xiaomi OpenWear Stereo Pro", XiaomiEarbudAdapter.ID, false),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 4", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 4 Active", XiaomiEarbudAdapter.ID, false),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 4 Pro", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 5", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 5 Pro", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 6", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 6 Active", XiaomiEarbudAdapter.ID, false),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 6 Lite", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 6 Play", XiaomiEarbudAdapter.ID, false),
        ThirdPartyModelSpec("Redmi", "xiaomi", "Redmi Buds 6 Pro", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "REDMI Buds 8", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "REDMI Buds 8 Active", XiaomiEarbudAdapter.ID, false),
        ThirdPartyModelSpec("Redmi", "xiaomi", "REDMI Buds 8 Lite", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "REDMI Buds 8 Pro", XiaomiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Redmi", "xiaomi", "REDMI Headphones Neo", XiaomiEarbudAdapter.ID, true),

        // 华为 / 荣耀
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds 3", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds 4", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds 4i", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds 5", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds 5i", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds 6i", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds Pro", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds Pro 2", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds Pro 3", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds Pro 4", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeBuds SE 2", HuaweiEarbudAdapter.ID, false),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeLace Pro", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("华为", "huawei", "HUAWEI FreeLace Pro 2", HuaweiEarbudAdapter.ID, true),
        ThirdPartyModelSpec("荣耀", "huawei", "HONOR Earbuds 2 Lite", HonorEarbudAdapter.ID, true),
        ThirdPartyModelSpec("荣耀", "huawei", "HONOR Earbuds 3 Pro", HonorEarbudAdapter.ID, true),
        ThirdPartyModelSpec("荣耀", "huawei", "HONOR Earbuds X5s Pro", HonorEarbudAdapter.ID, true),
        ThirdPartyModelSpec("荣耀", "huawei", "HONOR Earbuds X6", HonorEarbudAdapter.ID, false),
        ThirdPartyModelSpec("荣耀", "huawei", "HONOR Earbuds X7", HonorEarbudAdapter.ID, false),

        // Sony
        ThirdPartyModelSpec("Sony", "sony", "Sony WF-1000XM3", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony WF-1000XM4", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony WF-1000XM5", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony WH-1000XM3", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony WH-1000XM4", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony WH-1000XM5", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony WH-CH720N", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony WF-C500", SonyEarbudAdapter.ID, false),
        ThirdPartyModelSpec("Sony", "sony", "Sony WF-C700N", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony LinkBuds", SonyEarbudAdapter.ID, false),
        ThirdPartyModelSpec("Sony", "sony", "Sony LinkBuds S", SonyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Sony", "sony", "Sony ULT WEAR", SonyEarbudAdapter.ID, true),

        // 三星
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds", SamsungEarbudAdapter.ID, false),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds+", SamsungEarbudAdapter.ID, false),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds Live", SamsungEarbudAdapter.ID, true),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds Pro", SamsungEarbudAdapter.ID, true),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds2", SamsungEarbudAdapter.ID, true),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds2 Pro", SamsungEarbudAdapter.ID, true),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds FE", SamsungEarbudAdapter.ID, true),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds3", SamsungEarbudAdapter.ID, true),
        ThirdPartyModelSpec("三星", "others", "Galaxy Buds3 Pro", SamsungEarbudAdapter.ID, true),

        // Bose
        ThirdPartyModelSpec("Bose", "others", "Bose QuietComfort Earbuds", BoseEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Bose", "others", "Bose QuietComfort Earbuds II", BoseEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Bose", "others", "Bose QuietComfort Ultra Earbuds", BoseEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Bose", "others", "Bose QuietComfort 45", BoseEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Bose", "others", "Bose Noise Cancelling Headphones 700", BoseEarbudAdapter.ID, true),

        // 漫步者
        ThirdPartyModelSpec("漫步者", "others", "EDIFIER NeoBuds Pro", EdifierEarbudAdapter.ID, true),
        ThirdPartyModelSpec("漫步者", "others", "EDIFIER NeoBuds Pro 2", EdifierEarbudAdapter.ID, true),
        ThirdPartyModelSpec("漫步者", "others", "EDIFIER NeoBuds S", EdifierEarbudAdapter.ID, true),
        ThirdPartyModelSpec("漫步者", "others", "EDIFIER LolliPods Pro 2", EdifierEarbudAdapter.ID, true),
        ThirdPartyModelSpec("漫步者", "others", "EDIFIER FitBuds Pro", EdifierEarbudAdapter.ID, true),

        // 水月雨
        ThirdPartyModelSpec("水月雨", "others", "MOONDROP 知更鸟 Robin", MoondropEarbudAdapter.ID, true),
        ThirdPartyModelSpec("水月雨", "others", "MOONDROP 太空漫游 Space Travel", MoondropEarbudAdapter.ID, true),
        ThirdPartyModelSpec("水月雨", "others", "MOONDROP 梦回 Golden Ages", MoondropEarbudAdapter.ID, true),
        ThirdPartyModelSpec("水月雨", "others", "MOONDROP 梦想碟 Pudding", MoondropEarbudAdapter.ID, true),

        // QCY
        ThirdPartyModelSpec("QCY", "others", "QCY MeloBuds ANC (HT05)", QcyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("QCY", "others", "QCY MeloBuds Pro (HT08)", QcyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("QCY", "others", "QCY HT07 ArcBuds", QcyEarbudAdapter.ID, true),
        ThirdPartyModelSpec("QCY", "others", "QCY T13 ANC", QcyEarbudAdapter.ID, true),

        // 弱水时砂
        ThirdPartyModelSpec("弱水时砂", "others", "弱水时砂 Earfree i3", RoseEarbudAdapter.ID, true),
        ThirdPartyModelSpec("弱水时砂", "others", "弱水时砂 Earfree i5", RoseEarbudAdapter.ID, true),
        ThirdPartyModelSpec("弱水时砂", "others", "弱水时砂 琉璃 X (Ceramics X)", RoseEarbudAdapter.ID, true),
        ThirdPartyModelSpec("弱水时砂", "others", "弱水时砂 BudsFeel", RoseEarbudAdapter.ID, true),

        // Technics
        ThirdPartyModelSpec("Technics", "others", "Technics EAH-AZ40M2", TechnicsEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Technics", "others", "Technics EAH-AZ60", TechnicsEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Technics", "others", "Technics EAH-AZ60M2", TechnicsEarbudAdapter.ID, true),
        ThirdPartyModelSpec("Technics", "others", "Technics EAH-AZ80", TechnicsEarbudAdapter.ID, true),
    )
}
