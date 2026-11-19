package com.originpods.integration

import com.originpods.protocol.oppo.OppoWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OppoProtocolSessionTest {
    @Test
    fun `resolves exact model and falls back cleanly when disabled`() {
        val identity = EarbudIdentity(address = "00:11:22:33:44:55", deviceName = "OPPO Enco X3")
        val exact = EarbudAdapterRegistry.resolve(identity)
        assertEquals(OppoEncoX3Adapter.ID, exact?.id)

        val family = EarbudAdapterRegistry.resolve(identity, disabledAdapterIds = setOf(OppoEncoX3Adapter.ID))
        assertEquals(OppoEarbudAdapter.ID, family?.id)
    }

    @Test
    fun `handles air2 pro reversed anc mapping and battery merge`() {
        val adapter = OppoEncoAir2ProAdapter()
        val session = adapter.createProtocolSession()
        assertNotNull(session)
        session!!

        val setAncCmd = session.encode(ControlRequest.SetNoiseMode(NoiseMode.ANC)).single()
        val frames = OppoWireCodec.Decoder().offer(setAncCmd)
        assertEquals(0x01, frames.single().payload[2].toInt() and 0xFF)

        val reportLeft = OppoWireCodec.packet(
            command = OppoWireCodec.ACTIVE_REPORT,
            payload = byteArrayOf(0x01, 0x01, 0x01, 0x46),
        )
        val events = session.offer(reportLeft)
        val batteryState = events.filterIsInstance<ProtocolEvent.FeatureStateChanged>()
            .mapNotNull { it.state as? BatteryFeatureState }
            .single()
        assertEquals(70, batteryState.battery.left.percent)
        assertTrue(!batteryState.battery.right.available)
    }

    @Test
    fun `loads bundled oppo family whitelist and resolves dynamic anc by product id`() {
        assertTrue(OppoModelCatalog.allModels().size > 50)
        assertEquals("OPPO Enco Air5s（星光版）", OppoModelCatalog.byDeviceName("OPPO Enco Laplace 3")?.name)
        assertEquals(
            OppoEarbudAdapter.ID,
            EarbudAdapterRegistry.resolve(EarbudIdentity("00:11:22:33:44:55", "realme Buds Air8"))?.id,
        )
        assertEquals(
            OnePlusEarbudAdapter.ID,
            EarbudAdapterRegistry.resolve(EarbudIdentity("00:11:22:33:44:55", "OnePlus Nord Buds 4"))?.id,
        )

        val session = OppoEarbudAdapter().createProtocolSession()
        // Simulate 0x8103 productId = 0x067410 -> little-endian bytes: 0x00, 0x10, 0x74, 0x06
        val productRsp = OppoWireCodec.packet(
            command = OppoWireCodec.PRODUCT_ID_RESPONSE,
            payload = byteArrayOf(0x00, 0x10, 0x74, 0x06),
        )
        val events = session.offer(productRsp)
        assertTrue(events.any { it is ProtocolEvent.HandshakeAccepted })

        val encAnc = session.encode(ControlRequest.SetNoiseMode(NoiseMode.ANC)).single()
        val ancFrame = OppoWireCodec.Decoder().offer(encAnc).single()
        assertEquals(OppoWireCodec.SET_ANC, ancFrame.command)
    }
}
