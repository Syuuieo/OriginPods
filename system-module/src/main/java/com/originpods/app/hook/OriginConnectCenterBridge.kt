package com.originpods.app.hook

import android.util.Log
import com.originpods.integration.NoiseMode
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Bridges [RemotePodState] into OriginOS `VivoTws` -> `ConnectCenter` JSON/Binder contracts.
 */
object OriginConnectCenterBridge {
    private const val TAG = "OriginPods.Bridge"
    const val SERVICE_BATTERY = "battery"
    const val SERVICE_NOISE_CONTROL = "twsconn_noice_reduction"

    @Volatile
    private var vivoAdapterServiceRef: WeakReference<Any>? = null

    fun attachVivoAdapterService(service: Any) {
        vivoAdapterServiceRef = WeakReference(service)
    }

    fun pushState(mac: String, state: RemotePodState) {
        val service = vivoAdapterServiceRef?.get() ?: return
        runCatching {
            // VivoAdapterService.f11258h is ConnHub (om.b); om.b.f31385a is a8.c (ConnectCenter client)
            val connHubField = service.javaClass.getDeclaredField("h").apply { isAccessible = true }
            val connHub = connHubField.get(service) ?: return
            val clientField = connHub.javaClass.getDeclaredField("a").apply { isAccessible = true }
            val client = clientField.get(connHub) ?: return
            val pushMethod = client.javaClass.getDeclaredMethod("F", String::class.java, String::class.java)

            buildBatteryPropertyJson(state)?.let { batteryProp ->
                val businessJson = JSONObject().apply {
                    put("connections", buildConnectionsArray(mac))
                    put("propertyChange", batteryProp)
                }.toString()
                pushMethod.invoke(client, mac, businessJson)
            }

            buildNoisePropertyJson(state)?.let { noiseProp ->
                val businessJson = JSONObject().apply {
                    put("connections", buildConnectionsArray(mac))
                    put("propertyChange", noiseProp)
                }.toString()
                pushMethod.invoke(client, mac, businessJson)
            }
        }.onFailure { err ->
            Log.w(TAG, "pushState failed for $mac: ${err.message}")
        }
    }

    fun buildPropertyListJson(state: RemotePodState): String {
        val list = JSONArray()
        buildBatteryPropertyJson(state)?.let { list.put(it) }
        buildNoisePropertyJson(state)?.let { list.put(it) }
        return JSONObject().apply {
            put("propertyJson", list)
        }.toString()
    }

    private fun buildConnectionsArray(mac: String): JSONArray =
        JSONArray().put(
            JSONObject().apply {
                put("deviceId", mac)
                put("connectType", 1)
                put("connectState", 2)
            },
        )

    private fun buildBatteryPropertyJson(state: RemotePodState): JSONObject? {
        if (state.left < 0 && state.right < 0 && state.box < 0) return null
        val all = listOf(state.left, state.right).filter { it >= 0 }.minOrNull() ?: -1
        return JSONObject().apply {
            put("property", SERVICE_BATTERY)
            put("propertyName", "")
            put("headsetLeft", state.left)
            put("headsetRight", state.right)
            put("headsetBox", state.box)
            put("headsetAll", all)
            put("chargingState", state.chargingState)
            put("earphoneType", 2)
            put("wearType", -1)
        }
    }

    private fun buildNoisePropertyJson(state: RemotePodState): JSONObject? {
        val mode = state.noiseMode ?: return null
        val option = when (mode) {
            NoiseMode.ANC,
            NoiseMode.ANC_DEEP,
            NoiseMode.ANC_MEDIUM,
            NoiseMode.ANC_LIGHT,
            NoiseMode.WIND,
            -> "key_noice_reduction"
            NoiseMode.ANC_ADAPTIVE -> "key_auto"
            NoiseMode.TRANSPARENCY -> "key_transparent"
            NoiseMode.OFF -> "key_close"
        }
        return JSONObject().apply {
            put("property", SERVICE_NOISE_CONTROL)
            put("propertyName", "降噪控制")
            put("option", option)
        }
    }
}
