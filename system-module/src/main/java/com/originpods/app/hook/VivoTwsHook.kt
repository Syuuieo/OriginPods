package com.originpods.app.hook

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.originpods.integration.EarbudAdapterRegistry
import com.originpods.integration.EarbudIdentity
import com.originpods.integration.NoiseMode
import com.originpods.integration.StandardEarbudAdapter
import com.originpods.protocol.oppo.OppoWireCodec
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * Hooks `com.android.vivo.tws.vivotws` (`TwsConfigClient`, `ConnectionService`, `VivoAdapterService`,
 * and `ConnectCenterObserverImpl` `pm.a`) so OriginOS treats supported OPPO/OnePlus earbuds as
 * connected native vivo TWS devices.
 */
class VivoTwsHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) : ProcessHook {

    private val cachedStates = ConcurrentHashMap<String, RemotePodState>()
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var capturedModel: Int = 0
    @Volatile
    private var activePresenterRef: java.lang.ref.WeakReference<Any>? = null

    override fun install() {
        Handler(Looper.getMainLooper()).postDelayed({
            val app = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()
            if (app != null) {
                appContext = app
                registerStateReceiver(app)
            }
        }, 1000L)

        hookTwsConfigClient()
        hookVivoBluetoothUtils()
        hookHomeDeviceBeanAndPresenter()
        hookVipcRouter()
        hookConnectionService()
        hookVivoAdapterService()
        hookConnectCenterObserver()
    }

    private fun isSupportedOppoMac(mac: String?): Boolean {
        if (mac.isNullOrEmpty()) return false
        if (cachedStates.containsKey(mac)) return true
        val dev = runCatching {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
        }.getOrNull() ?: return false
        val name = runCatching { dev.name }.getOrNull()
        return isSupportedOppoName(name)
    }

    private fun stateOrFallback(mac: String): RemotePodState? {
        cachedStates[mac]?.let { return it }
        if (!isSupportedOppoMac(mac)) return null
        val name = runCatching {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)?.name
        }.getOrNull() ?: mac
        return RemotePodState(mac = mac, name = name)
    }

    private fun isSupportedOppoName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val adapter = EarbudAdapterRegistry.resolve(
            EarbudIdentity(address = "00:00:00:00:00:00", deviceName = name),
        ) ?: return false
        return adapter.id != StandardEarbudAdapter.ID
    }

    private fun hookVivoBluetoothUtils() {
        val utilsClass = runCatching { classLoader.loadClass("or.g") }.getOrNull() ?: return
        listOf("d", "e").forEach { methodName ->
            runCatching {
                val m = utilsClass.getDeclaredMethod(methodName, BluetoothDevice::class.java)
                module.hook(m).intercept { chain ->
                    val res = chain.proceed()
                    if (res == true) return@intercept true
                    val dev = chain.args.getOrNull(0) as? BluetoothDevice ?: return@intercept false
                    val name = runCatching { dev.name }.getOrNull()
                    if (cachedStates.containsKey(dev.address) || isSupportedOppoName(name)) {
                        return@intercept true
                    }
                    false
                }
            }
        }
        module.log(Log.INFO, TAG, "hooked or.g (VivoTws VivoBluetoothUtils)")
    }

    private fun hookHomeDeviceBeanAndPresenter() {
        runCatching {
            val beanClass = classLoader.loadClass("com.vivo.tws.settings.home.bean.HomeDeviceBean")
            val isConnected = beanClass.getDeclaredMethod("isConnected")
            val getDevice = beanClass.getDeclaredMethod("getDevice")
            module.hook(isConnected).intercept { chain ->
                val res = chain.proceed()
                if (res == true) return@intercept true
                val dev = runCatching { getDevice.invoke(chain.thisObject) as? BluetoothDevice }.getOrNull()
                if (dev != null && isSupportedOppoMac(dev.address)) {
                    return@intercept true
                }
                false
            }
            module.log(Log.INFO, TAG, "hooked HomeDeviceBean.isConnected")
        }

        runCatching {
            val zClass = classLoader.loadClass("hp.z")
            val configBeanClass = classLoader.loadClass("com.vivo.commonbase.bean.TwsConfig\$TwsConfigBean")
            val b0Method = zClass.getDeclaredMethod("b0", configBeanClass)
            module.hook(b0Method).intercept { chain ->
                chain.thisObject?.let { activePresenterRef = java.lang.ref.WeakReference(it) }
                val cfg = chain.args.getOrNull(0)
                if (cfg != null) {
                    runCatching {
                        val m = cfg.javaClass.getMethod("getModel").invoke(cfg) as? Int ?: 0
                        if (m != 0) capturedModel = m
                    }
                }
                chain.proceed()
            }
            module.log(Log.INFO, TAG, "hooked HomePresenter.b0")
        }
    }

    private fun resolveVivoModelId(): Int {
        if (capturedModel != 0) return capturedModel
        return runCatching {
            val earbudClass = classLoader.loadClass("com.vivo.config.TwsConfigClient\$EARBUD")
            val cfg = earbudClass.getDeclaredMethod("getConfigByDeviceName", String::class.java)
                .invoke(null, "vivo TWS 4")
            (cfg?.javaClass?.getMethod("getModel")?.invoke(cfg) as? Int ?: 0).also {
                if (it != 0) capturedModel = it
            }
        }.getOrDefault(0)
    }

    private fun hookVipcRouter() {
        val vipcClass = runCatching { classLoader.loadClass("as.c") }.getOrNull() ?: return
        val reqClass = runCatching { classLoader.loadClass("com.vivo.vipc.databus.request.Request") }.getOrNull()
            ?: return
        val cbClass = runCatching { classLoader.loadClass("as.a") }.getOrNull() ?: return
        val requestMeta = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<Any, Triple<String, String, String>>(),
        )

        listOf("a", "b").forEach { methodName ->
            runCatching {
                val m = vipcClass.getDeclaredMethod(
                    methodName,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                )
                module.hook(m).intercept { chain ->
                    val provider = chain.args.getOrNull(0) as? String
                    val cmd = chain.args.getOrNull(1) as? String
                    val mac = chain.args.getOrNull(2) as? String
                    val param = chain.args.getOrNull(3) as? String
                    val req = if (provider == "config_feature" && cmd == "get_config_by_name" &&
                        ((mac != null && cachedStates.containsKey(mac)) || isSupportedOppoName(param))
                    ) {
                        m.invoke(null, provider, cmd, mac, "vivo TWS 4")
                    } else {
                        chain.proceed()
                    }
                    if (req != null && cmd != null && mac != null) {
                        requestMeta[req] = Triple(cmd, mac, param.orEmpty())
                    }
                    req
                }
            }
        }

        runCatching {
            val cMethod = vipcClass.getDeclaredMethod("c", reqClass, cbClass)
            module.hook(cMethod).intercept { chain ->
                val req = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
                val cb = chain.args.getOrNull(1)
                val (cmd, mac, param) = requestMeta.remove(req) ?: return@intercept chain.proceed()

                val state = stateOrFallback(mac)
                if (state != null) {
                    val handled = handleVipcCommand(cmd, mac, param, state)
                    if (handled != null) {
                        module.log(Log.INFO, TAG, "handled VipcHelper cmd=$cmd mac=$mac")
                        runCatching {
                            cb?.javaClass?.getMethod("onResponse", String::class.java)?.invoke(cb, handled)
                        }
                        return@intercept null
                    }
                }
                chain.proceed()
            }
            module.log(Log.INFO, TAG, "hooked as.c (VivoTws VipcHelper)")
        }
    }

    private fun handleVipcCommand(cmd: String, mac: String, param: String, state: RemotePodState): String? {
        return when (cmd) {
            "get_earbud_status" -> buildEarbudStatusJson(state)
            "get_settings" -> {
                val model = resolveVivoModelId()
                val (noiseMode, reduceModel) = when (state.noiseMode) {
                    NoiseMode.OFF -> 1 to 0
                    NoiseMode.TRANSPARENCY -> 3 to 0
                    NoiseMode.ANC_ADAPTIVE -> 2 to 0
                    NoiseMode.ANC_DEEP -> 2 to 3
                    NoiseMode.ANC_MEDIUM -> 2 to 2
                    NoiseMode.ANC_LIGHT -> 2 to 1
                    NoiseMode.ANC, NoiseMode.WIND -> 2 to 2
                    null -> 1 to 0
                }
                org.json.JSONObject().apply {
                    put("noise_mode_config", noiseMode)
                    put("reduce_noise_model_config", reduceModel)
                    put("wear_monitor_switch", 1)
                    put("attr", org.json.JSONObject().put("model", model))
                }.toString()
            }
            "get_connection_state" -> "1"
            "set_noise_mode" -> {
                val parts = param.split(",").mapNotNull { it.trim().toIntOrNull() }
                val mode = when (parts.getOrNull(0)) {
                    1 -> NoiseMode.OFF
                    3 -> NoiseMode.TRANSPARENCY
                    2 -> when (parts.getOrNull(1)) {
                        0 -> NoiseMode.ANC_ADAPTIVE
                        1 -> NoiseMode.ANC_LIGHT
                        2 -> NoiseMode.ANC_MEDIUM
                        3 -> NoiseMode.ANC_DEEP
                        else -> NoiseMode.ANC
                    }
                    else -> NoiseMode.ANC
                }
                cachedStates[mac] = state.copy(noiseMode = mode)
                appContext?.sendBroadcast(
                    Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                        putExtra("mac", mac)
                        putExtra("noise_mode", mode.name)
                    },
                )
                "0"
            }
            "set_low_latency_gaming" -> {
                appContext?.sendBroadcast(
                    Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                        putExtra("mac", mac)
                        putExtra("feature_code", OppoWireCodec.FEATURE_GAME_MODE)
                        putExtra("feature_enabled", param == "1")
                    },
                )
                "0"
            }
            "set_audio_play", "set_audio_play_state" -> {
                appContext?.sendBroadcast(
                    Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                        putExtra("mac", mac)
                        putExtra("find_buds", param == "1")
                    },
                )
                "0"
            }
            "set_audio_effect" -> {
                val preset = when (param.toIntOrNull()) {
                    1 -> 28
                    2 -> 29
                    3 -> 34
                    else -> 26
                }
                appContext?.sendBroadcast(
                    Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                        putExtra("mac", mac)
                        putExtra("eq_preset", preset)
                    },
                )
                "0"
            }
            "set_wear_monitor_switch", "set_wear_monitor" -> {
                appContext?.sendBroadcast(
                    Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                        putExtra("mac", mac)
                        putExtra("feature_code", OppoWireCodec.FEATURE_IN_EAR)
                        putExtra("feature_enabled", param == "1")
                    },
                )
                "0"
            }
            "set_noise_anti_wind_switch", "set_anti_wind" -> {
                appContext?.sendBroadcast(
                    Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                        putExtra("mac", mac)
                        putExtra("feature_code", OppoWireCodec.FEATURE_WIND_NOISE)
                        putExtra("feature_enabled", param == "1")
                    },
                )
                "0"
            }
            else -> null
        }
    }

    private fun hookTwsConfigClient() {
        val earbudClass = runCatching {
            classLoader.loadClass("com.vivo.config.TwsConfigClient\$EARBUD")
        }.getOrNull() ?: return

        val getByName = runCatching {
            earbudClass.getDeclaredMethod("getConfigByDeviceName", String::class.java)
        }.getOrNull() ?: return

        module.hook(getByName).intercept { chain ->
            val result = chain.proceed()
            if (result != null) return@intercept result
            val deviceName = chain.args.getOrNull(0) as? String ?: return@intercept null
            val adapter = EarbudAdapterRegistry.resolve(
                EarbudIdentity(address = "00:00:00:00:00:00", deviceName = deviceName),
            )
            if (adapter != null && adapter.id != StandardEarbudAdapter.ID) {
                module.log(Log.INFO, TAG, "spoofing EarbudConfig for $deviceName -> vivo TWS 4")
                return@intercept getByName.invoke(null, "vivo TWS 4")
            }
            null
        }
    }

    private fun hookConnectionService() {
        val connServiceClass = runCatching {
            classLoader.loadClass("com.vivo.tws.conn.bean.ConnectionService")
        }.getOrNull() ?: return

        runCatching {
            val isConnected = connServiceClass.getDeclaredMethod("isConnected", String::class.java)
            module.hook(isConnected).intercept { chain ->
                val result = chain.proceed()
                if (result == true) return@intercept true
                val mac = chain.args.getOrNull(0) as? String ?: return@intercept false
                if (isSupportedOppoMac(mac)) {
                    return@intercept true
                }
                false
            }
        }

        runCatching {
            val getConnection = connServiceClass.getDeclaredMethod("getConnection", String::class.java)
            val updateConnected = connServiceClass.getDeclaredMethod(
                "updateConnected",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
            module.hook(getConnection).intercept { chain ->
                val result = chain.proceed()
                if (result != null) return@intercept result
                val mac = chain.args.getOrNull(0) as? String ?: return@intercept null
                if (isSupportedOppoMac(mac)) {
                    updateConnected.invoke(chain.thisObject, mac, true)
                    return@intercept chain.proceed()
                }
                null
            }
        }
    }

    private fun hookVivoAdapterService() {
        val serviceClass = runCatching {
            classLoader.loadClass("com.android.vivo.tws.vivotws.service.VivoAdapterService")
        }.getOrNull() ?: return

        val onCreate = runCatching { serviceClass.getDeclaredMethod("onCreate") }.getOrNull() ?: return
        module.hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            val service = chain.thisObject
            if (service is Context) {
                appContext = service.applicationContext
                OriginConnectCenterBridge.attachVivoAdapterService(service)
                registerStateReceiver(service.applicationContext)
            }
            result
        }
    }

    private fun hookConnectCenterObserver() {
        val observerClass = runCatching {
            classLoader.loadClass("pm.a")
        }.getOrNull() ?: return

        runCatching {
            val uMethod = observerClass.getDeclaredMethod("u", String::class.java)
            module.hook(uMethod).intercept { chain ->
                val mac = chain.args.getOrNull(0) as? String
                if (mac != null && cachedStates.containsKey(mac)) {
                    return@intercept arrayListOf(
                        OriginConnectCenterBridge.SERVICE_BATTERY,
                        OriginConnectCenterBridge.SERVICE_NOISE_CONTROL,
                    )
                }
                chain.proceed()
            }
        }

        runCatching {
            val tMethod = observerClass.getDeclaredMethod("t", String::class.java, String::class.java)
            module.hook(tMethod).intercept { chain ->
                val mac = chain.args.getOrNull(0) as? String
                val serviceId = chain.args.getOrNull(1) as? String
                if (mac != null &&
                    serviceId == OriginConnectCenterBridge.SERVICE_NOISE_CONTROL &&
                    cachedStates.containsKey(mac)
                ) {
                    return@intercept arrayListOf("anc", "off", "transparency")
                }
                chain.proceed()
            }
        }

        runCatching {
            val rMethod = observerClass.getDeclaredMethod("r", String::class.java)
            module.hook(rMethod).intercept { chain ->
                val mac = chain.args.getOrNull(0) as? String
                val state = mac?.let { cachedStates[it] }
                if (mac != null && state != null) {
                    return@intercept OriginConnectCenterBridge.buildPropertyListJson(state)
                }
                chain.proceed()
            }
        }

        runCatching {
            val callbackClass = classLoader.loadClass("com.vivo.connbase.connectcenter.b0")
            val dispatchMethod = observerClass.getDeclaredMethod("s", String::class.java, callbackClass)
            module.hook(dispatchMethod).intercept { chain ->
                val actionJson = chain.args.getOrNull(0) as? String
                val callback = chain.args.getOrNull(1)
                if (actionJson != null && forwardActionToBluetooth(actionJson)) {
                    runCatching {
                        callback?.javaClass?.getMethod("b", Int::class.javaPrimitiveType, String::class.java)
                            ?.invoke(callback, 0, "")
                    }
                    return@intercept null
                }
                chain.proceed()
            }
        }
    }

    private fun forwardActionToBluetooth(actionJson: String): Boolean {
        if (!actionJson.contains("twsconn_noice_reduction")) return false
        val targetMac = cachedStates.keys.firstOrNull { actionJson.contains(it) }
            ?: cachedStates.keys.firstOrNull()
            ?: return false
        val mode = when {
            actionJson.contains("anc", ignoreCase = true) || actionJson.contains("noise_reduction") -> NoiseMode.ANC
            actionJson.contains("transparency", ignoreCase = true) || actionJson.contains("hear") -> NoiseMode.TRANSPARENCY
            actionJson.contains("off", ignoreCase = true) || actionJson.contains("close") -> NoiseMode.OFF
            else -> return false
        }
        val ctx = appContext ?: return false
        ctx.sendBroadcast(
            Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                putExtra("mac", targetMac)
                putExtra("noise_mode", mode.name)
            },
        )
        module.log(Log.INFO, TAG, "forwarded control mac=$targetMac mode=$mode")
        return true
    }

    private fun registerStateReceiver(context: Context) {
        val filter = IntentFilter(OriginPodsXposedEntry.ACTION_STATE_CHANGED)
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val mac = intent.getStringExtra("mac") ?: return
                        val name = intent.getStringExtra("name") ?: mac
                        val prev = cachedStates[mac]
                        val next = RemotePodState(
                            mac = mac,
                            name = name,
                            left = intent.getIntExtra("left", prev?.left ?: -1),
                            right = intent.getIntExtra("right", prev?.right ?: -1),
                            box = intent.getIntExtra("box", prev?.box ?: -1),
                            chargingState = intent.getIntExtra("charging_state", prev?.chargingState ?: 0),
                            noiseMode = intent.getStringExtra("noise_mode")
                                ?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() }
                                ?: prev?.noiseMode,
                        )
                        cachedStates[mac] = next
                        OriginConnectCenterBridge.pushState(mac, next)
                        if (next.left >= 0 || next.right >= 0 || next.box >= 0) {
                            pushBatteryToActivePresenter(mac, next)
                        }
                        module.log(Log.INFO, TAG, "updated state in VivoTws mac=$mac L=${next.left} R=${next.right}")
                    }
                },
                filter,
                Context.RECEIVER_EXPORTED,
            )
            context.sendBroadcast(
                Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                    putExtra("request_state", true)
                },
            )
        }
    }

    private fun buildEarbudStatusJson(state: RemotePodState): String {
        val model = resolveVivoModelId()
        return org.json.JSONObject().apply {
            put("left_battery", state.left)
            put("right_battery", state.right)
            put("box_battery", state.box)
            put("charge_state", state.chargingState)
            put("ear_state", 0x0C)
            put("is_info_from_earbud", true)
            put("attr", org.json.JSONObject().put("model", model))
        }.toString()
    }

    private fun pushBatteryToActivePresenter(mac: String, state: RemotePodState) {
        val presenter = activePresenterRef?.get() ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val bean = presenter.javaClass.getMethod("E").invoke(presenter) ?: return@post
                val device = bean.javaClass.getMethod("getDevice").invoke(bean) as? BluetoothDevice ?: return@post
                if (!device.address.equals(mac, ignoreCase = true)) return@post
                val yMethod = presenter.javaClass.declaredMethods.firstOrNull {
                    it.name == "Y" && it.parameterCount == 1
                } ?: return@post
                val statusClass = yMethod.parameterTypes[0]
                val gsonClass = classLoader.loadClass("com.google.gson.Gson")
                val gson = gsonClass.getDeclaredConstructor().newInstance()
                val statusObj = gsonClass.getMethod("fromJson", String::class.java, Class::class.java)
                    .invoke(gson, buildEarbudStatusJson(state), statusClass) ?: return@post
                yMethod.isAccessible = true
                yMethod.invoke(presenter, statusObj)
                module.log(Log.INFO, TAG, "pushed battery to HomePresenter mac=$mac L=${state.left} R=${state.right} B=${state.box}")
            }.onFailure {
                module.log(Log.WARN, TAG, "pushBatteryToActivePresenter failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "OriginPods.VivoTws"
    }
}
