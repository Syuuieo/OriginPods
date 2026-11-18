package com.originpods.app.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.originpods.integration.ControlRequest
import com.originpods.integration.ProtocolEvent
import com.originpods.integration.ProtocolSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Manages a single Bluetooth Classic RFCOMM socket connection for a [ProtocolSession].
 */
class RfcommTransport(
    val device: BluetoothDevice,
    private val serviceUuid: String,
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
                val uuid = UUID.fromString(serviceUuid)
                val rfcomm = device.createRfcommSocketToServiceRecord(uuid)
                socket = rfcomm
                rfcomm.connect()
                Log.i(TAG, "RFCOMM connected to ${device.address} ($serviceUuid)")

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

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "OriginPods.RFCOMM"
    }
}
