package com.originpods.app.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.originpods.app.runtime.ActiveDeviceSessionState
import com.originpods.app.runtime.OriginDeviceSessionManager
import com.originpods.integration.BatteryFeatureState
import com.originpods.integration.ControlRequest
import com.originpods.integration.EqPresetFeatureState
import com.originpods.integration.FindBudsFeatureState
import com.originpods.integration.FirmwareFeatureState
import com.originpods.integration.NoiseMode
import com.originpods.integration.NoiseModeFeatureState
import com.originpods.integration.OppoTogglesFeatureState
import io.github.libxposed.api.XposedModule

/**
 * Runs inside `com.android.bluetooth` (always alive) to own the single RFCOMM session
 * for OPPO/OnePlus earbuds and broadcast normalized state to OriginOS processes.
 */
@SuppressLint("MissingPermission")
class BluetoothProcessHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) : ProcessHook {

    @Volatile
    private var appContext: Context? = null
    private val ringingStates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override fun install() {
        Handler(Looper.getMainLooper()).postDelayed({
            val app = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()

            if (app == null) {
                module.log(Log.WARN, TAG, "currentApplication is null in com.android.bluetooth")
                return@postDelayed
            }

            OriginDeviceSessionManager.onSessionUpdated = { mac, state ->
                broadcastState(app, mac, state)
                pushToVivoBatteryHelper(mac, state)
            }

            registerReceivers(app)
            scanConnectedA2dp(app)
            hookVivoBatteryHelperObserver()
            module.log(Log.INFO, TAG, "BluetoothProcessHook initialized")
        }, 1500L)
    }

    private fun hookVivoBatteryHelperObserver() {
        val observerClass = runCatching {
            classLoader.loadClass(
                "com.android.bluetooth.vivo.VivoBatteryHelper\$ConnbaseConnector\$ConnectCenterObserver",
            )
        }.getOrNull() ?: return

        runCatching {
            val m = observerClass.getDeclaredMethod("createSupportServiceList", String::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val mac = chain.args.getOrNull(0) as? String
                if (mac != null && OriginDeviceSessionManager.activeDevices.value.containsKey(mac)) {
                    return@intercept arrayListOf("twsconn_noice_reduction", "vdfs_find_earphone")
                }
                chain.proceed()
            }
        }

        runCatching {
            val m = observerClass.getDeclaredMethod(
                "createSupportOptionList",
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val mac = chain.args.getOrNull(0) as? String
                val serviceId = chain.args.getOrNull(1) as? String
                if (mac != null &&
                    serviceId == "twsconn_noice_reduction" &&
                    OriginDeviceSessionManager.activeDevices.value.containsKey(mac)
                ) {
                    return@intercept arrayListOf(
                        "key_noice_reduction",
                        "key_auto",
                        "key_transparent",
                        "key_close",
                    )
                }
                chain.proceed()
            }
        }

        runCatching {
            val m = observerClass.getDeclaredMethod("createPropertyJson", String::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val orig = chain.proceed() as? String ?: return@intercept null
                val mac = chain.args.getOrNull(0) as? String ?: return@intercept orig
                val session = OriginDeviceSessionManager.activeDevices.value[mac] ?: return@intercept orig
                val mode = session.snapshot.get<NoiseModeFeatureState>()?.mode
                val ringing = ringingStates[mac] ?: false
                runCatching {
                    val root = org.json.JSONObject(orig)
                    val arr = root.optJSONArray("propertyJson") ?: org.json.JSONArray()
                    if (mode != null) {
                        arr.put(
                            org.json.JSONObject().apply {
                                put("property", "twsconn_noice_reduction")
                                put("propertyName", "降噪控制")
                                put("option", mode.toOptionKey())
                            },
                        )
                    }
                    arr.put(
                        org.json.JSONObject().apply {
                            put("property", "vdfs_find_earphone")
                            put("propertyName", "查找耳机")
                            put("option", if (ringing) "key_start_ring" else "key_stop_ring")
                        },
                    )
                    root.put("propertyJson", arr)
                    root.toString()
                }.getOrDefault(orig)
            }
        }

        runCatching {
            val cbClass = classLoader.loadClass("com.vivo.connbase.connectcenter.IServiceCallback")
            val m = observerClass.getDeclaredMethod("onServiceDispatch", String::class.java, cbClass)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val json = chain.args.getOrNull(0) as? String
                val cb = chain.args.getOrNull(1)
                if (json != null && handleServiceDispatch(json)) {
                    runCatching {
                        cb?.javaClass?.getMethod("onResult", Int::class.javaPrimitiveType, String::class.java)
                            ?.invoke(cb, 0, "success")
                    }
                    return@intercept null
                }
                chain.proceed()
            }
        }

        module.log(Log.INFO, TAG, "hooked VivoBatteryHelper ConnectCenterObserver")
    }

    private fun handleServiceDispatch(json: String): Boolean {
        return runCatching {
            val obj = org.json.JSONObject(json)
            val serviceId = obj.optString("serviceId")
            val mac = obj.optString("deviceId")
            if (!OriginDeviceSessionManager.activeDevices.value.containsKey(mac)) return false
            val option = obj.optString("option")
            when (serviceId) {
                "twsconn_noice_reduction" -> {
                    val mode = when (option) {
                        "key_noice_reduction", "anc" -> NoiseMode.ANC
                        "key_auto", "adaptive" -> NoiseMode.ANC_ADAPTIVE
                        "key_transparent", "transparency" -> NoiseMode.TRANSPARENCY
                        "key_close", "off" -> NoiseMode.OFF
                        else -> return false
                    }
                    module.log(Log.INFO, TAG, "ConnectCenter noise dispatch mac=$mac option=$option -> $mode")
                    OriginDeviceSessionManager.sendRequest(mac, ControlRequest.SetNoiseMode(mode))
                    true
                }
                "vdfs_find_earphone" -> {
                    val ringing = when (option) {
                        "key_start_ring" -> true
                        "key_stop_ring" -> false
                        else -> return false
                    }
                    ringingStates[mac] = ringing
                    module.log(Log.INFO, TAG, "ConnectCenter find_earphone dispatch mac=$mac ringing=$ringing")
                    OriginDeviceSessionManager.sendRequest(mac, ControlRequest.SetFindBuds(ringing))
                    reportPropertyChange(mac, "vdfs_find_earphone", "查找耳机", option)
                    true
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun reportPropertyChange(mac: String, property: String, propertyName: String, option: String) {
        runCatching {
            val helperClass = classLoader.loadClass("com.android.bluetooth.vivo.VivoBatteryHelper")
            val helper = helperClass.getMethod("getInstance").invoke(null) ?: return
            val connector = helperClass.getDeclaredField("mConnbaseConnector").apply { isAccessible = true }.get(helper)
                ?: return
            val api = connector.javaClass.getDeclaredField("mConnectCenterApi").apply { isAccessible = true }.get(connector)
                ?: return
            val propJson = org.json.JSONObject().apply {
                put(
                    "propertyChange",
                    org.json.JSONObject().apply {
                        put("property", property)
                        put("propertyName", propertyName)
                        put("option", option)
                    },
                )
            }.toString()
            api.javaClass.getMethod(
                "reportBusinessInfo",
                String::class.java,
                String::class.java,
                classLoader.loadClass("com.vivo.connbase.connectcenter.ICallbackResult"),
            ).invoke(api, mac, propJson, null)
        }
    }

    private fun NoiseMode.toOptionKey(): String = when (this) {
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

    private fun registerReceivers(context: Context) {
        val btFilter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    ) ?: return
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            OriginDeviceSessionManager.onDeviceDisconnected(device.address)
                            removeFromVivoBatteryHelper(device)
                        }
                        else -> {
                            if (state == BluetoothProfile.STATE_CONNECTED) {
                                module.log(Log.INFO, TAG, "BT connected: ${device.name} (${device.address})")
                                OriginDeviceSessionManager.onDeviceConnected(device)
                            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                OriginDeviceSessionManager.onDeviceDisconnected(device.address)
                                removeFromVivoBatteryHelper(device)
                            }
                        }
                    }
                }
            },
            btFilter,
            Context.RECEIVER_EXPORTED,
        )

        val ctrlFilter = IntentFilter(OriginPodsXposedEntry.ACTION_CONTROL)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getBooleanExtra("request_state", false)) {
                        OriginDeviceSessionManager.activeDevices.value.forEach { (devMac, devState) ->
                            broadcastState(ctx, devMac, devState)
                        }
                        return
                    }
                    val mac = intent.getStringExtra("mac") ?: return
                    intent.getStringExtra("noise_mode")?.let { modeName ->
                        val mode = runCatching { NoiseMode.valueOf(modeName) }.getOrNull() ?: return@let
                        module.log(Log.INFO, TAG, "control request mac=$mac mode=$mode")
                        OriginDeviceSessionManager.sendRequest(mac, ControlRequest.SetNoiseMode(mode))
                    }
                    if (intent.hasExtra("find_buds")) {
                        val ringing = intent.getBooleanExtra("find_buds", false)
                        ringingStates[mac] = ringing
                        module.log(Log.INFO, TAG, "control request mac=$mac find_buds=$ringing")
                        OriginDeviceSessionManager.sendRequest(mac, ControlRequest.SetFindBuds(ringing))
                        reportPropertyChange(
                            mac,
                            "vdfs_find_earphone",
                            "查找耳机",
                            if (ringing) "key_start_ring" else "key_stop_ring",
                        )
                    }
                    if (intent.hasExtra("feature_code")) {
                        val code = intent.getIntExtra("feature_code", -1)
                        val enabled = intent.getBooleanExtra("feature_enabled", false)
                        if (code >= 0) {
                            module.log(Log.INFO, TAG, "control request mac=$mac feature=$code enabled=$enabled")
                            OriginDeviceSessionManager.sendRequest(
                                mac,
                                ControlRequest.SetFeatureToggle(code, enabled),
                            )
                        }
                    }
                    if (intent.hasExtra("eq_preset")) {
                        val preset = intent.getIntExtra("eq_preset", -1)
                        if (preset >= 0) {
                            module.log(Log.INFO, TAG, "control request mac=$mac eq_preset=$preset")
                            OriginDeviceSessionManager.sendRequest(mac, ControlRequest.SetEqPreset(preset))
                        }
                    }
                }
            },
            ctrlFilter,
            Context.RECEIVER_EXPORTED,
        )
    }

    private fun scanConnectedA2dp(context: Context) {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val adapter = manager.adapter ?: return
        adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching {
                        proxy.connectedDevices.forEach { device ->
                            module.log(Log.INFO, TAG, "scan A2DP connected: ${device.name} (${device.address})")
                            OriginDeviceSessionManager.onDeviceConnected(device)
                        }
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            },
            BluetoothProfile.A2DP,
        )
    }

    private fun broadcastState(context: Context, mac: String, state: ActiveDeviceSessionState) {
        val battery = state.snapshot.get<BatteryFeatureState>()?.battery
        val mode = state.snapshot.get<NoiseModeFeatureState>()?.mode
        val firmware = state.snapshot.get<FirmwareFeatureState>()?.version
        val eqPreset = state.snapshot.get<EqPresetFeatureState>()?.presetId
        val toggles = state.snapshot.get<OppoTogglesFeatureState>()?.toggles
        val intent = Intent(OriginPodsXposedEntry.ACTION_STATE_CHANGED).apply {
            putExtra("mac", mac)
            putExtra("name", state.identity.deviceName ?: mac)
            if (battery != null) {
                putExtra("left", battery.left.percent ?: -1)
                putExtra("right", battery.right.percent ?: -1)
                putExtra("box", battery.case.percent ?: -1)
                var cs = 0
                if (battery.left.charging) cs = cs or 1
                if (battery.right.charging) cs = cs or 2
                if (battery.case.charging) cs = cs or 4
                putExtra("charging_state", cs)
            }
            if (mode != null) {
                putExtra("noise_mode", mode.name)
            }
            if (firmware != null) {
                putExtra("firmware", firmware)
            }
            if (eqPreset != null) {
                putExtra("eq_preset", eqPreset)
            }
            if (toggles != null) {
                putExtra("toggles_keys", toggles.keys.toIntArray())
                putExtra("toggles_vals", toggles.values.toBooleanArray())
            }
            putExtra("ringing", ringingStates[mac] ?: false)
        }
        context.sendBroadcast(intent)
        module.log(
            Log.INFO,
            TAG,
            "broadcastState mac=$mac L=${battery?.left?.percent} R=${battery?.right?.percent} B=${battery?.case?.percent} mode=$mode",
        )
    }

    private fun pushToVivoBatteryHelper(mac: String, state: ActiveDeviceSessionState) {
        val battery = state.snapshot.get<BatteryFeatureState>()?.battery ?: return
        runCatching {
            val helperClass = classLoader.loadClass("com.android.bluetooth.vivo.VivoBatteryHelper")
            val helper = helperClass.getMethod("getInstance").invoke(null) ?: return
            val adapter = BluetoothManager::class.java
                .let { helperClass.getDeclaredField("mBluetoothAdapter").apply { isAccessible = true }.get(helper) as? android.bluetooth.BluetoothAdapter }
                ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return
            val device = adapter.getRemoteDevice(mac)

            val mapField = helperClass.getDeclaredField("mDeviceBatteryRecordMap").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val map = mapField.get(helper) as? MutableMap<BluetoothDevice, Any> ?: return

            val recordClass = classLoader.loadClass("com.android.bluetooth.vivo.VivoBatteryHelper\$DeviceBatteryRecord")
            var record = map[device]
            if (record == null) {
                val ctor = recordClass.getDeclaredConstructor(helperClass, BluetoothDevice::class.java)
                    .apply { isAccessible = true }
                record = ctor.newInstance(helper, device)
                map[device] = record
            }

            fun setIntField(name: String, value: Int) {
                recordClass.getDeclaredField(name).apply { isAccessible = true }.setInt(record, value)
            }
            var cs = 0
            if (battery.left.charging) cs = cs or 1
            if (battery.right.charging) cs = cs or 2
            if (battery.case.charging) cs = cs or 4

            setIntField("mLeftBattery", battery.left.percent ?: -1)
            setIntField("mRightBattery", battery.right.percent ?: -1)
            setIntField("mCaseBattery", battery.case.percent ?: -1)
            setIntField("mChargeStatus", cs)
            setIntField("mWearType", 2)
            setIntField("mInEarStatus", -1)

            val connector = helperClass.getDeclaredField("mConnbaseConnector").apply { isAccessible = true }.get(helper)
                ?: return
            val connectorClass = connector.javaClass
            val isSupported = connectorClass.getDeclaredField("isAirpodsSupported")
                .apply { isAccessible = true }.getBoolean(connector)
            val api = connectorClass.getDeclaredField("mConnectCenterApi")
                .apply { isAccessible = true }.get(connector)
            val isBindField = recordClass.getDeclaredField("mIsBind").apply { isAccessible = true }
            val alreadyBound = isBindField.getBoolean(record)
            if (!alreadyBound) {
                connectorClass.getMethod("bindDevice", BluetoothDevice::class.java).invoke(connector, device)
                isBindField.setBoolean(record, true)
                module.log(Log.INFO, TAG, "VivoBatteryHelper.bindDevice($mac) supported=$isSupported api=${api != null}")
            }
            connectorClass.getMethod(
                "reportDeviceStatus",
                BluetoothDevice::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(connector, device, true)

            state.snapshot.get<NoiseModeFeatureState>()?.mode?.let { mode ->
                val propJson = org.json.JSONObject().apply {
                    put(
                        "propertyChange",
                        org.json.JSONObject().apply {
                            put("property", "twsconn_noice_reduction")
                            put("propertyName", "降噪控制")
                            put("option", mode.toOptionKey())
                        },
                    )
                }.toString()
                api?.javaClass?.getMethod(
                    "reportBusinessInfo",
                    String::class.java,
                    String::class.java,
                    classLoader.loadClass("com.vivo.connbase.connectcenter.ICallbackResult"),
                )?.invoke(api, mac, propJson, null)
            }
            module.log(Log.INFO, TAG, "VivoBatteryHelper.reportDeviceStatus($mac, true) supported=$isSupported")
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "pushToVivoBatteryHelper failed: ${err.message}")
        }
    }

    private fun removeFromVivoBatteryHelper(device: BluetoothDevice) {
        runCatching {
            val helperClass = classLoader.loadClass("com.android.bluetooth.vivo.VivoBatteryHelper")
            val helper = helperClass.getMethod("getInstance").invoke(null) ?: return
            val mapField = helperClass.getDeclaredField("mDeviceBatteryRecordMap").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val map = mapField.get(helper) as? MutableMap<BluetoothDevice, Any>
            map?.remove(device)
            val connector = helperClass.getDeclaredField("mConnbaseConnector").apply { isAccessible = true }.get(helper)
                ?: return
            connector.javaClass.getMethod(
                "reportDeviceStatus",
                BluetoothDevice::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(connector, device, false)
        }
    }

    companion object {
        private const val TAG = "OriginPods.BT"
    }
}
