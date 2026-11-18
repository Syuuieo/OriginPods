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
}
