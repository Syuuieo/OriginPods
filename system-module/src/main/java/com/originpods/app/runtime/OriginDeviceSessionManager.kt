package com.originpods.app.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.originpods.integration.ControlRequest
import com.originpods.integration.DeviceFeatureSnapshot
import com.originpods.integration.EarbudAdapter
import com.originpods.integration.EarbudAdapterRegistry
import com.originpods.integration.EarbudIdentity
import com.originpods.integration.NoiseMode
import com.originpods.integration.ProtocolEvent
import com.originpods.integration.RfcommEndpointSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ActiveDeviceSessionState(
    val identity: EarbudIdentity,
    val adapter: EarbudAdapter,
    val snapshot: DeviceFeatureSnapshot = DeviceFeatureSnapshot(),
    val supportedNoiseModes: Set<NoiseMode> = emptySet(),
    val handshakeAccepted: Boolean = false,
)

object OriginDeviceSessionManager {
    private const val TAG = "OriginPods.Session"

    private val sessions = ConcurrentHashMap<String, RfcommTransport>()
    private val _activeDevices = MutableStateFlow<Map<String, ActiveDeviceSessionState>>(emptyMap())
    val activeDevices: StateFlow<Map<String, ActiveDeviceSessionState>> = _activeDevices.asStateFlow()

    var disabledAdapterIds: Set<String> = emptySet()
    var onSessionUpdated: ((String, ActiveDeviceSessionState) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun onDeviceConnected(device: BluetoothDevice) {
        val address = device.address ?: return
        val existing = sessions[address]
        if (existing != null && existing.isRunning) return
        existing?.stop()

        val identity = EarbudIdentity(
            address = address,
            deviceName = runCatching { device.name }.getOrNull(),
        )
        val adapter = EarbudAdapterRegistry.resolve(identity, disabledAdapterIds) ?: return
        Log.i(TAG, "matched ${identity.deviceName} ($address) -> ${adapter.id}")

        _activeDevices.value = _activeDevices.value + (
            address to ActiveDeviceSessionState(identity = identity, adapter = adapter)
        )

        val session = adapter.createProtocolSession() ?: return
        val endpoint = adapter.transports.filterIsInstance<RfcommEndpointSpec.ServiceUuid>().firstOrNull()
            ?: return

        val transport = RfcommTransport(
            device = device,
            serviceUuid = endpoint.uuid,
            session = session,
            onEvents = { events -> handleEvents(address, events) },
        )
        sessions[address] = transport
        transport.start()
    }

    fun onDeviceDisconnected(address: String?) {
        if (address.isNullOrBlank()) return
        sessions.remove(address)?.stop()
        _activeDevices.value = _activeDevices.value - address
    }

    fun sendRequest(address: String, request: ControlRequest) {
        val transport = sessions[address]
        if (transport != null && transport.send(request)) return
        val device = transport?.device ?: return
        Log.i(TAG, "restarting RFCOMM transport before sending $request to $address")
        transport.stop()
        sessions.remove(address)
        onDeviceConnected(device)
        sessions[address]?.send(request)
    }

    fun tryHandleConnectCenterAction(actionJson: String): Boolean {
        if (!actionJson.contains("twsconn_noice_reduction")) return false
        val targetAddress = _activeDevices.value.keys.firstOrNull() ?: return false
        val mode = when {
            actionJson.contains("anc", ignoreCase = true) || actionJson.contains("noise_reduction") -> NoiseMode.ANC
            actionJson.contains("transparency", ignoreCase = true) || actionJson.contains("hear") -> NoiseMode.TRANSPARENCY
            actionJson.contains("off", ignoreCase = true) || actionJson.contains("close") -> NoiseMode.OFF
            else -> return false
        }
        sendRequest(targetAddress, ControlRequest.SetNoiseMode(mode))
        return true
    }

    private fun handleEvents(address: String, events: List<ProtocolEvent>) {
        val current = _activeDevices.value[address] ?: return
        var updated = current
        events.forEach { event ->
            when (event) {
                ProtocolEvent.HandshakeAccepted -> {
                    updated = updated.copy(handshakeAccepted = true)
                }
                is ProtocolEvent.CapabilitiesIdentified -> {
                    if (event.noiseModes.isNotEmpty()) {
                        updated = updated.copy(supportedNoiseModes = event.noiseModes)
                    }
                }
                is ProtocolEvent.FeatureStateChanged -> {
                    updated = updated.copy(snapshot = updated.snapshot.update(event.state))
                }
                is ProtocolEvent.UnknownFrame -> Unit
            }
        }
        _activeDevices.value = _activeDevices.value + (address to updated)
        onSessionUpdated?.invoke(address, updated)
    }
}
