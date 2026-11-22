package com.originpods.app.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import com.originpods.integration.ControlRequest
import com.originpods.integration.EarbudTransportSpec
import com.originpods.integration.L2capEndpointSpec
import com.originpods.integration.ProtocolEvent
import com.originpods.integration.ProtocolSession
import com.originpods.integration.RfcommEndpointSpec
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Manages a single Bluetooth Classic RFCOMM or BR/EDR L2CAP socket connection for a [ProtocolSession].
 */
class RfcommTransport(
    val device: BluetoothDevice,
    private val endpoint: EarbudTransportSpec,
    private val session: ProtocolSession,
    private val onEvents: (List<ProtocolEvent>) -> Unit,
    private val onClosed: (() -> Unit)? = null,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "RFCOMM coroutine error for ${device.address}: ${throwable.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var ioJob: Job? = null

    val isRunning: Boolean
        get() = ioJob?.isActive == true && socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun start() {
        if (ioJob?.isActive == true) return
        ioJob = scope.launch {
            var writer: Job? = null
            try {
                val rfcomm = when (endpoint) {
                    is RfcommEndpointSpec.ServiceUuid -> {
                        val uuid = UUID.fromString(endpoint.uuid)
                        device.createRfcommSocketToServiceRecord(uuid)
                    }
                    is RfcommEndpointSpec.Channel -> {
                        val methodName = if (endpoint.secure) "createRfcommSocket" else "createInsecureRfcommSocket"
                        device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
                            .invoke(device, endpoint.number) as BluetoothSocket
                    }
                    is L2capEndpointSpec -> createL2capSocket(device, endpoint)
                }
                socket = rfcomm
                rfcomm.connect()
                Log.i(TAG, "RFCOMM connected to ${device.address} (${endpoint.id})")

                val output = rfcomm.outputStream
                val input = rfcomm.inputStream

                session.initialReadCommands().forEach { writeQueue.offer(it) }

                writer = launch {
                    try {
                        while (isActive) {
                            val packet = writeQueue.take()
                            output.write(packet)
                            output.flush()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "RFCOMM writer stopped for ${device.address}: ${e.message}")
                        closeSocket()
                    }
                }

                val buffer = ByteArray(1024)
                while (isActive) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val chunk = buffer.copyOfRange(0, read)
                    val events = session.offer(chunk)
                    if (events.isNotEmpty()) {
                        events.forEach { event ->
                            session.followUpCommands(event).forEach { writeQueue.offer(it) }
                        }
                        onEvents(events)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "RFCOMM closed for ${device.address}: ${e.message}")
            } catch (e: Throwable) {
                Log.w(TAG, "RFCOMM unexpected error for ${device.address}: ${e.message}")
            } finally {
                writer?.cancel()
                closeSocket()
                onClosed?.invoke()
            }
        }
    }

    fun send(request: ControlRequest): Boolean {
        if (!isRunning) return false
        session.encode(request).forEach { writeQueue.offer(it) }
        session.readback(request).forEach { writeQueue.offer(it) }
        return true
    }

    fun stop() {
        ioJob?.cancel()
        ioJob = null
        closeSocket()
        session.reset()
        scope.cancel()
    }

    private fun createL2capSocket(
        device: BluetoothDevice,
        endpoint: L2capEndpointSpec,
    ): BluetoothSocket {
        val uuid = ParcelUuid.fromString(endpoint.serviceUuid)
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val candidates = buildList {
            if (adapter != null) {
                add(
                    arrayOf<Any>(
                        adapter,
                        device,
                        BLUETOOTH_SOCKET_TYPE_L2CAP,
                        endpoint.authenticated,
                        endpoint.encrypted,
                        endpoint.psm,
                        uuid,
                    ),
                )
            }
            add(
                arrayOf<Any>(
                    device,
                    BLUETOOTH_SOCKET_TYPE_L2CAP,
                    endpoint.authenticated,
                    endpoint.encrypted,
                    endpoint.psm,
                    uuid,
                ),
            )
            add(
                arrayOf<Any>(
                    device,
                    BLUETOOTH_SOCKET_TYPE_L2CAP,
                    INVALID_FILE_DESCRIPTOR,
                    endpoint.authenticated,
                    endpoint.encrypted,
                    endpoint.psm,
                    uuid,
                ),
            )
            add(
                arrayOf<Any>(
                    BLUETOOTH_SOCKET_TYPE_L2CAP,
                    INVALID_FILE_DESCRIPTOR,
                    endpoint.authenticated,
                    endpoint.encrypted,
                    device,
                    endpoint.psm,
                    uuid,
                ),
            )
            add(
                arrayOf<Any>(
                    BLUETOOTH_SOCKET_TYPE_L2CAP,
                    endpoint.authenticated,
                    endpoint.encrypted,
                    device,
                    endpoint.psm,
                    uuid,
                ),
            )
        }
        candidates.forEach { args ->
            val paramTypes = args.map { arg ->
                when (arg) {
                    is Int -> Int::class.javaPrimitiveType!!
                    is Boolean -> Boolean::class.javaPrimitiveType!!
                    is BluetoothAdapter -> BluetoothAdapter::class.java
                    is BluetoothDevice -> BluetoothDevice::class.java
                    is ParcelUuid -> ParcelUuid::class.java
                    else -> arg.javaClass
                }
            }.toTypedArray()
            val constructor = try {
                BluetoothSocket::class.java.getDeclaredConstructor(*paramTypes)
            } catch (_: NoSuchMethodException) {
                return@forEach
            }
            try {
                return constructor.apply { isAccessible = true }.newInstance(*args) as BluetoothSocket
            } catch (error: InvocationTargetException) {
                throw error.cause ?: error
            }
        }
        throw NoSuchMethodException("No supported BluetoothSocket L2CAP constructor")
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "OriginPods.RFCOMM"
        private const val BLUETOOTH_SOCKET_TYPE_L2CAP = 3
        private const val INVALID_FILE_DESCRIPTOR = -1
    }
}
