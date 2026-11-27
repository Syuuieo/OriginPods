package com.originpods.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiBrandRegistryTest {
    @Test
    fun resolvesAllUpstreamBrands() {
        val cases = listOf(
            "HUAWEI FreeBuds Pro 3" to HuaweiEarbudAdapter.ID,
            "HONOR Earbuds X5s Pro" to HonorEarbudAdapter.ID,
            "WF-1000XM5" to SonyEarbudAdapter.ID,
            "Galaxy Buds2 Pro" to SamsungEarbudAdapter.ID,
            "Bose QuietComfort Earbuds" to BoseEarbudAdapter.ID,
            "EDIFIER NeoBuds Pro 2" to EdifierEarbudAdapter.ID,
            "MOONDROP Robin" to MoondropEarbudAdapter.ID,
            "MOONDROP MIRAGE" to MoondropMirageAdapter.ID,
            "MOONDROP PILL" to MoondropPillAdapter.ID,
            "ZZZ-ANGELS-OWS" to MoondropZzzAngelsAdapter.ID,
            "NiceHCK Origin" to NiceHckYuanDaoOrigAdapter.ID,
            "StarRing Ultra" to StarRingUltraAdapter.ID,
            "QCY MeloBuds ANC" to QcyEarbudAdapter.ID,
            "Earfree i5" to RoseEarbudAdapter.ID,
            "EAH-AZ80" to TechnicsEarbudAdapter.ID,
            "Redmi Buds 5 Pro" to XiaomiEarbudAdapter.ID,
        )
        for ((name, expectedId) in cases) {
            val adapter = EarbudAdapterRegistry.resolve(
                EarbudIdentity(address = "11:22:33:44:55:66", deviceName = name),
            )
            assertNotNull("Expected adapter for $name", adapter)
            assertEquals("Wrong adapter for $name", expectedId, adapter?.id)
            val session = adapter?.createProtocolSession()
            assertNotNull("Expected protocol session for $name", session)
            assertTrue("Expected initial commands for $name", session!!.initialReadCommands().isNotEmpty())
        }
    }

    @Test
    fun everyThirdPartyCatalogModelResolvesToExpectedAdapter() {
        for (model in MultiBrandModelCatalog.models) {
            val resolved = EarbudAdapterRegistry.resolve(
                EarbudIdentity(address = "11:22:33:44:55:66", deviceName = model.name),
            )
            assertEquals("Mismatch for ${model.name}", model.adapterId, resolved?.id)
        }
    }
}
