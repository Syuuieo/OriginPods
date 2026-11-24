package com.originpods.app.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.originpods.integration.ControlRequest
import com.originpods.integration.DeviceFeatureSnapshot
import com.originpods.integration.EarbudAdapter
import com.originpods.integration.EarbudAdapterRegistry
import com.originpods.integration.EarbudIdentity
import com.originpods.integration.L2capEndpointSpec
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
    private const val MAX_RECONNECT_ATTEMPTS = 5
    private val RECONNECT_DELAYS_MS = longArrayOf(2000L, 4000L, 8000L, 15000L, 30000L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, RfcommTransport>()
    private val knownDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val pendingReconnects = ConcurrentHashMap<String, Runnable>()
    private val _activeDevices = MutableStateFlow<Map<String, ActiveDeviceSessionState>>(emptyMap())
    val activeDevices: StateFlow<Map<String, ActiveDeviceSessionState>> = _activeDevices.asStateFlow()

    var disabledAdapterIds: Set<String> = emptySet()
    var onSessionUpdated: ((String, ActiveDeviceSessionState) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun onDeviceConnected(device: BluetoothDevice) {
        val address = device.address ?: return
        pendingReconnects.remove(address)?.let { mainHandler.removeCallbacks(it) }
        val existing = sessions[address]
        if (existing != null && existing.isRunning) return
        existing?.stop()

        val uuids = runCatching { device.uuids?.map { it.uuid.toString() } }.getOrNull().orEmpty()
        val btClass = runCatching { device.bluetoothClass }.getOrNull()
        val identity = EarbudIdentity(
            address = address,
            deviceName = runCatching { device.name }.getOrNull(),
            serviceUuids = uuids,
            majorDeviceClass = btClass?.majorDeviceClass,
            deviceClass = btClass?.deviceClass,
        )
        val adapter = EarbudAdapterRegistry.resolve(identity, disabledAdapterIds) ?: return
        Log.i(TAG, "matched ${identity.deviceName} ($address) -> ${adapter.id}")
        knownDevices[address] = device

        val existingState = _activeDevices.value[address]
        val initialState = existingState?.copy(identity = identity, adapter = adapter)
            ?: ActiveDeviceSessionState(identity = identity, adapter = adapter)
        _activeDevices.value = _activeDevices.value + (address to initialState)
        onSessionUpdated?.invoke(address, initialState)

        val session = adapter.createProtocolSession() ?: return
        val endpoint = adapter.transports.firstOrNull {
            it is RfcommEndpointSpec || it is L2capEndpointSpec
        } ?: return

        val transport = RfcommTransport(
            device = device,
            endpoint = endpoint,
            session = session,
            onEvents = { events -> handleEvents(address, events) },
            onClosed = { scheduleReconnectIfActive(address) },
        )
        sessions[address] = transport
        transport.start()
    }

    private fun scheduleReconnectIfActive(address: String) {
        if (!_activeDevices.value.containsKey(address)) return
        val device = knownDevices[address] ?: return
        val attempt = reconnectAttempts[address] ?: 0
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "RFCOMM reconnect exhausted ($attempt attempts) for $address")
            return
        }
        if (pendingReconnects.containsKey(address)) return
        reconnectAttempts[address] = attempt + 1
        val delayMs = RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        Log.i(TAG, "scheduling RFCOMM reconnect #${attempt + 1} for $address in ${delayMs}ms")
        val task = Runnable {
            pendingReconnects.remove(address)
            if (_activeDevices.value.containsKey(address)) {
                onDeviceConnected(device)
            }
        }
        pendingReconnects[address] = task
        mainHandler.postDelayed(task, delayMs)
    }

    fun onDeviceDisconnected(address: String?) {
        if (address.isNullOrBlank()) return
        pendingReconnects.remove(address)?.let { mainHandler.removeCallbacks(it) }
        reconnectAttempts.remove(address)
        knownDevices.remove(address)
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
                    reconnectAttempts.remove(address)
                    updated = updated.copy(handshakeAccepted = true)
                }
                ProtocolEvent.HandshakeRejected -> {
                    updated = updated.copy(handshakeAccepted = false)
                }
                is ProtocolEvent.ProductIdentified -> Unit
                is ProtocolEvent.CapabilitiesIdentified -> {
                    if (event.noiseModes.isNotEmpty()) {
                        updated = updated.copy(supportedNoiseModes = event.noiseModes)
                    }
                }
                is ProtocolEvent.FeatureStateChanged -> {
                    reconnectAttempts.remove(address)
                    updated = updated.copy(snapshot = updated.snapshot.update(event.state))
                }
                is ProtocolEvent.UnknownFrame -> Unit
            }
        }
        _activeDevices.value = _activeDevices.value + (address to updated)
        onSessionUpdated?.invoke(address, updated)
    }
}
