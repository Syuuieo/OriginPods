package com.originpods.app.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.originpods.integration.NoiseMode
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

data class RemotePodState(
    val mac: String,
    val name: String,
    val left: Int = -1,
    val right: Int = -1,
    val box: Int = -1,
    val chargingState: Int = 0,
    val noiseMode: NoiseMode? = null,
)

/**
 * Hooks `com.vivo.connbase.connectcenter` to inject OPPO/OnePlus battery & ANC state
 * received from `BluetoothProcessHook` into `ControlDeviceInfo`.
 */
class ConnectCenterHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) : ProcessHook {

    private val cachedStates = ConcurrentHashMap<String, RemotePodState>()
    @Volatile
    private var contextRef: Context? = null

    override fun install() {
        Handler(Looper.getMainLooper()).postDelayed({
            val app = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()
            if (app != null) {
                contextRef = app
                registerStateReceiver(app)
            }
        }, 1000L)

        hookControlParse()
    }

    private fun registerStateReceiver(context: Context) {
        val filter = IntentFilter(OriginPodsXposedEntry.ACTION_STATE_CHANGED)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val mac = intent.getStringExtra("mac") ?: return
                    val name = intent.getStringExtra("name") ?: mac
                    val prev = cachedStates[mac]
                    val left = intent.getIntExtra("left", prev?.left ?: -1)
                    val right = intent.getIntExtra("right", prev?.right ?: -1)
                    val box = intent.getIntExtra("box", prev?.box ?: -1)
                    val cs = intent.getIntExtra("charging_state", prev?.chargingState ?: 0)
                    val mode = intent.getStringExtra("noise_mode")
                        ?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() }
                        ?: prev?.noiseMode
                    cachedStates[mac] = RemotePodState(mac, name, left, right, box, cs, mode)
                    module.log(Log.INFO, TAG, "received state mac=$mac L=$left R=$right B=$box mode=$mode")
                }
            },
            filter,
            Context.RECEIVER_EXPORTED,
        )
        module.log(Log.INFO, TAG, "registered state receiver in ConnectCenter")
    }

    private fun hookControlParse() {
        val controlDeviceInfoClass = runCatching {
            classLoader.loadClass("com.vivo.connectcenter.common.bean.ControlDeviceInfo")
        }.getOrNull() ?: return

        val twsParseClass = runCatching {
            classLoader.loadClass("s3.y")
        }.getOrNull() ?: return

        runCatching {
            val tMethod = twsParseClass.getDeclaredMethod("t", controlDeviceInfoClass)
            module.hook(tMethod).intercept { chain ->
                val result = chain.proceed()
                val info = chain.args.getOrNull(0)
                if (info != null) {
                    applyCachedState(info)
                }
                result
            }
            module.log(Log.INFO, TAG, "hooked TwsControlParse.t")
        }

        // Fix/inject OptionControl SoundItems for twsconn_noice_reduction
        runCatching {
            val utilsClass = classLoader.loadClass("com.vivo.connect.center.utils.i")
            val optionControlClass = classLoader.loadClass("com.vivo.connect.center.model.OptionControl")
            val baseDeviceClass = classLoader.loadClass("com.vivo.connectcenter.common.bean.BaseDevice")
            val soundItemClass = classLoader.loadClass("com.vivo.connect.center.model.SoundItem")
            val pMethod = utilsClass.getDeclaredMethod(
                "p",
                optionControlClass,
                baseDeviceClass,
                String::class.java,
            )
            module.hook(pMethod).intercept { chain ->
                val result = chain.proceed()
                val optionControl = chain.args.getOrNull(0) ?: return@intercept result
                val baseDevice = chain.args.getOrNull(1) ?: return@intercept result
                val serviceId = chain.args.getOrNull(2) as? String ?: return@intercept result
                if (serviceId == OriginConnectCenterBridge.SERVICE_NOISE_CONTROL) {
                    val currentName = runCatching {
                        optionControlClass.getMethod("getName").invoke(optionControl) as? String
                    }.getOrNull()
                    if (currentName.isNullOrEmpty()) {
                        optionControlClass.getMethod("setName", String::class.java).invoke(optionControl, "降噪控制")
                    }
                    @Suppress("UNCHECKED_CAST")
                    val existing = optionControlClass.getMethod("getListSounds").invoke(optionControl) as? List<Any>
                    val devId = baseDeviceClass.getMethod("getId").invoke(baseDevice) as? String
                    val podState = devId?.let { cachedStates[it] }
                    if (existing.isNullOrEmpty()) {
                        if (podState != null) {
                            val soundCtor = soundItemClass.getDeclaredConstructor(
                                String::class.java,
                                Boolean::class.javaPrimitiveType,
                                String::class.java,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                            )
                            val currentOpt = when (podState.noiseMode) {
                                NoiseMode.ANC, NoiseMode.ANC_DEEP, NoiseMode.ANC_MEDIUM, NoiseMode.ANC_LIGHT, NoiseMode.WIND -> "key_noice_reduction"
                                NoiseMode.ANC_ADAPTIVE -> "key_auto"
                                NoiseMode.TRANSPARENCY -> "key_transparent"
                                NoiseMode.OFF, null -> "key_close"
                            }
                            val items = arrayListOf(
                                "key_noice_reduction",
                                "key_auto",
                                "key_transparent",
                                "key_close",
                            ).mapNotNull { key ->
                                noiseIconAndLabel(key)?.let { (icon, label) ->
                                    soundCtor.newInstance(key, currentOpt == key, label, icon, icon)
                                }
                            }
                            optionControlClass.getMethod("setListSounds", List::class.java).invoke(optionControl, items)
                            module.log(Log.INFO, TAG, "injected OptionControl SoundItems for $devId")
                        }
                    } else {
                        existing.forEach { item -> fillSoundItemIfMissing(soundItemClass, item) }
                    }
                }
                result
            }
            module.log(Log.INFO, TAG, "hooked DOConverter.p")
        }

        // Also ensure OptionControlManager (s0) keeps icon & text across updates (b / e / a.b)
        runCatching {
            val s0Class = classLoader.loadClass("com.vivo.connect.center.control.s0")
            val holderClass = classLoader.loadClass("com.vivo.connect.center.control.s0\$a")
            val optionControlClass = classLoader.loadClass("com.vivo.connect.center.model.OptionControl")
            val soundItemClass = classLoader.loadClass("com.vivo.connect.center.model.SoundItem")

            val bHolderMethod = holderClass.getDeclaredMethod(
                "b",
                soundItemClass,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            module.hook(bHolderMethod).intercept { chain ->
                val soundItem = chain.args.getOrNull(0)
                if (soundItem != null) {
                    fillSoundItemIfMissing(soundItemClass, soundItem)
                }
                chain.proceed()
            }

            val bManagerMethod = s0Class.getDeclaredMethod("b", ArrayList::class.java)
                .apply { isAccessible = true }
            module.hook(bManagerMethod).intercept { chain ->
                val list = chain.args.getOrNull(0) as? List<*>
                list?.forEach { item ->
                    if (item != null) fillSoundItemIfMissing(soundItemClass, item)
                }
                chain.proceed()
            }

            val eManagerMethod = s0Class.getDeclaredMethod("e", String::class.java, optionControlClass)
                .apply { isAccessible = true }
            module.hook(eManagerMethod).intercept { chain ->
                val optionControl = chain.args.getOrNull(1)
                if (optionControl != null) {
                    val items = optionControlClass.getMethod("getListSounds").invoke(optionControl) as? List<*>
                    val hasNoiseKey = items?.any { item ->
                        val key = runCatching {
                            soundItemClass.getMethod("getSoundKey").invoke(item) as? String
                        }.getOrNull()
                        key == "key_noice_reduction" || key == "key_auto" || key == "key_transparent"
                    } == true
                    if (hasNoiseKey) {
                        val name = runCatching {
                            optionControlClass.getMethod("getName").invoke(optionControl) as? String
                        }.getOrNull()
                        if (name.isNullOrEmpty()) {
                            optionControlClass.getMethod("setName", String::class.java).invoke(optionControl, "降噪控制")
                        }
                        items?.forEach { item ->
                            if (item != null) fillSoundItemIfMissing(soundItemClass, item)
                        }
                    }
                }
                chain.proceed()
            }

            module.log(Log.INFO, TAG, "hooked OptionControlManager (s0.b, s0.e, s0\$a.b)")
        }

        // Intercept vdfs_find_earphone / twsconn_find_earphone in DeviceControlManager (u.h)
        // because ConnectCenter normally routes vdfs_find_earphone to com.vivo.findphone instead of Bluetooth.
        runCatching {
            val uClass = classLoader.loadClass("com.vivo.connect.center.control.u")
            val cbClass = classLoader.loadClass("com.vivo.cowork.callback.IResultCallback")
            val hMethod = uClass.getDeclaredMethod("h", String::class.java, cbClass)
                .apply { isAccessible = true }
            module.hook(hMethod).intercept { chain ->
                val json = chain.args.getOrNull(0) as? String
                val cb = chain.args.getOrNull(1)
                if (json != null && handleFindEarphoneDispatch(json)) {
                    runCatching {
                        cb?.javaClass?.getMethod("onResult", Int::class.javaPrimitiveType, String::class.java)
                            ?.invoke(cb, 0, "success")
                    }
                    return@intercept null
                }
                chain.proceed()
            }
            module.log(Log.INFO, TAG, "hooked DeviceControlManager.h for find_earphone")
        }
    }

    private fun handleFindEarphoneDispatch(json: String): Boolean {
        return runCatching {
            val obj = org.json.JSONObject(json)
            val serviceId = obj.optString("serviceId")
            if (serviceId != "vdfs_find_earphone" && serviceId != "twsconn_find_earphone") return false
            val mac = obj.optString("deviceId").takeIf { it.isNotEmpty() } ?: return false
            if (!cachedStates.containsKey(mac)) return false
            val option = obj.optString("option")
            val ringing = when (option) {
                "key_start_ring" -> true
                "key_stop_ring" -> false
                else -> return false
            }
            val ctx = contextRef ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()?.also { contextRef = it } ?: return false

            ctx.sendBroadcast(
                Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                    putExtra("mac", mac)
                    putExtra("find_buds", ringing)
                },
            )
            module.log(Log.INFO, TAG, "forwarded find_earphone mac=$mac ringing=$ringing")
            true
        }.getOrDefault(false)
    }

    private fun noiseIconAndLabel(key: String?): Pair<Int, String>? {
        val appCtx = contextRef ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.also { contextRef = it }
        val res = appCtx?.resources
        val pkg = "com.vivo.connbase.connectcenter"
        fun iconId(name: String, fallback: Int): Int =
            res?.getIdentifier(name, "drawable", pkg)?.takeIf { it != 0 } ?: fallback

        return when (key) {
            "key_noice_reduction" -> iconId("ic_noise_reduction", 0x7f080217) to "降噪"
            "key_auto" -> iconId("icon_noise_adaptive", 0x7f080234) to "自适应"
            "key_transparent" -> iconId("ic_noise_trans", 0x7f080218) to "通透"
            "key_close" -> iconId("ic_noise_close", 0x7f080216) to "关闭"
            else -> null
        }
    }

    private fun fillSoundItemIfMissing(soundItemClass: Class<*>, item: Any) {
        runCatching {
            val key = soundItemClass.getMethod("getSoundKey").invoke(item) as? String
            val (icon, label) = noiseIconAndLabel(key) ?: return
            val imgNormal = soundItemClass.getMethod("getImgNormal").invoke(item) as? Int ?: 0
            if (imgNormal <= 0) {
                soundItemClass.getMethod("setImgNormal", Int::class.javaPrimitiveType).invoke(item, icon)
                soundItemClass.getMethod("setImgHigh", Int::class.javaPrimitiveType).invoke(item, icon)
            }
            val text = soundItemClass.getMethod("getText").invoke(item) as? String
            if (text.isNullOrEmpty()) {
                soundItemClass.getMethod("setText", String::class.java).invoke(item, label)
            }
        }
    }

    private fun applyCachedState(info: Any) {
        val id = runCatching {
            info.javaClass.getMethod("getId").invoke(info) as? String
        }.getOrNull() ?: return

        val state = cachedStates[id] ?: return
        injectBattery(info, state)
        injectNoiseFunction(info, state)
    }

    private fun injectBattery(info: Any, state: RemotePodState) {
        val batteryClass = runCatching {
            classLoader.loadClass("com.vivo.connectcenter.common.bean.Battery")
        }.getOrNull() ?: return

        runCatching {
            val battery = batteryClass.getDeclaredConstructor().newInstance()
            val all = listOf(state.left, state.right).filter { it >= 0 }.minOrNull() ?: -1
            batteryClass.getMethod("setLeft", Int::class.javaPrimitiveType).invoke(battery, state.left)
            batteryClass.getMethod("setRight", Int::class.javaPrimitiveType).invoke(battery, state.right)
            batteryClass.getMethod("setBox", Int::class.javaPrimitiveType).invoke(battery, state.box)
            batteryClass.getMethod("setAll", Int::class.javaPrimitiveType).invoke(battery, all)
            batteryClass.getMethod("setChargingState", Int::class.javaPrimitiveType)
                .invoke(battery, state.chargingState)

            info.javaClass.getMethod("setBattery", batteryClass).invoke(info, battery)
        }
    }

    private fun injectNoiseFunction(info: Any, state: RemotePodState) {
        val mode = state.noiseMode ?: return
        val recentFnClass = runCatching {
            classLoader.loadClass("com.vivo.connectcenter.common.bean.RecentFunction")
        }.getOrNull() ?: return

        runCatching {
            val existing = info.javaClass.getMethod("getRecentFunction").invoke(info)
            val recent = existing ?: recentFnClass.getDeclaredConstructor().newInstance()
            val (option, label) = when (mode) {
                NoiseMode.ANC -> "key_noice_reduction" to "降噪"
                NoiseMode.ANC_ADAPTIVE -> "key_auto" to "自适应"
                NoiseMode.ANC_DEEP -> "key_noice_reduction" to "深度降噪"
                NoiseMode.ANC_MEDIUM -> "key_noice_reduction" to "中度降噪"
                NoiseMode.ANC_LIGHT -> "key_noice_reduction" to "轻度降噪"
                NoiseMode.TRANSPARENCY -> "key_transparent" to "通透"
                NoiseMode.OFF -> "key_close" to "关闭"
                NoiseMode.WIND -> "key_noice_reduction" to "抗风噪"
            }
            recentFnClass.getMethod("setId", String::class.java)
                .invoke(recent, OriginConnectCenterBridge.SERVICE_NOISE_CONTROL)
            recentFnClass.getMethod("setName", String::class.java).invoke(recent, label)
            recentFnClass.getMethod("setOption", String::class.java).invoke(recent, option)
            recentFnClass.getMethod("setAllOptions", Array<String>::class.java)
                .invoke(recent, arrayOf("key_noice_reduction", "key_auto", "key_transparent", "key_close"))

            info.javaClass.getMethod("setRecentFunction", recentFnClass).invoke(info, recent)
        }
    }

    companion object {
        private const val TAG = "OriginPods.CC"
    }
}
