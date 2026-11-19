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
        runCatching {
            val controlDeviceInfoClass = classLoader.loadClass("com.vivo.connectcenter.common.bean.ControlDeviceInfo")
            val twsParseClass = classLoader.loadClass("s3.y")
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
        // OriginOS 7: DOConverter (utils.i).p ; OriginOS 6 (1.5.4.0): DOConverter (utils.i).n
        runCatching {
            val utilsClass = classLoader.loadClass("com.vivo.connect.center.utils.i")
            val optionControlClass = classLoader.loadClass("com.vivo.connect.center.model.OptionControl")
            val baseDeviceClass = classLoader.loadClass("com.vivo.connectcenter.common.bean.BaseDevice")
            val soundItemClass = classLoader.loadClass("com.vivo.connect.center.model.SoundItem")
            for (methodName in listOf("p", "n")) {
                val method = runCatching {
                    utilsClass.getDeclaredMethod(
                        methodName,
                        optionControlClass,
                        baseDeviceClass,
                        String::class.java,
                    )
                }.getOrNull() ?: continue
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val optionControl = chain.args.getOrNull(0) ?: return@intercept result
                    val baseDevice = chain.args.getOrNull(1) ?: return@intercept result
                    val serviceId = chain.args.getOrNull(2) as? String ?: return@intercept result
                    handleOptionControlConvert(
                        optionControlClass,
                        baseDeviceClass,
                        soundItemClass,
                        optionControl,
                        baseDevice,
                        serviceId,
                    )
                    result
                }
                module.log(Log.INFO, TAG, "hooked DOConverter.$methodName")
            }
        }

        // Also ensure OptionControlManager keeps icon & text across updates:
        // OriginOS 7: com.vivo.connect.center.control.s0 (b, e(String, OptionControl), s0$a.b)
        // OriginOS 6 (1.5.4.0): i1.o0 (b, e(String, OptionControl, boolean), o0$a.b)
        val optionControlClass = runCatching {
            classLoader.loadClass("com.vivo.connect.center.model.OptionControl")
        }.getOrNull()
        val soundItemClass = runCatching {
            classLoader.loadClass("com.vivo.connect.center.model.SoundItem")
        }.getOrNull()
        if (optionControlClass != null && soundItemClass != null) {
            for (mgrName in listOf("com.vivo.connect.center.control.s0", "i1.o0")) {
                hookOptionControlManager(mgrName, optionControlClass, soundItemClass)
            }
        }

        // Intercept vdfs_find_earphone / twsconn_find_earphone in DeviceControlManager
        // OriginOS 7: com.vivo.connect.center.control.u.h ; OriginOS 6 (1.5.4.0): i1.l.g
        val cbClass = runCatching {
            classLoader.loadClass("com.vivo.cowork.callback.IResultCallback")
        }.getOrNull()
        if (cbClass != null) {
            val dispatchTargets = listOf(
                "com.vivo.connect.center.control.u" to "h",
                "i1.l" to "g",
            )
            for ((clsName, methodName) in dispatchTargets) {
                runCatching {
                    val targetClass = classLoader.loadClass(clsName)
                    val dispatchMethod = targetClass.getDeclaredMethod(methodName, String::class.java, cbClass)
                        .apply { isAccessible = true }
                    module.hook(dispatchMethod).intercept { chain ->
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
                    module.log(Log.INFO, TAG, "hooked $clsName.$methodName for find_earphone")
                }
            }
        }
    }

    private fun handleOptionControlConvert(
        optionControlClass: Class<*>,
        baseDeviceClass: Class<*>,
        soundItemClass: Class<*>,
        optionControl: Any,
        baseDevice: Any,
        serviceId: String,
    ) {
        if (serviceId != OriginConnectCenterBridge.SERVICE_NOISE_CONTROL) return
        val currentName = runCatching {
            optionControlClass.getMethod("getName").invoke(optionControl) as? String
        }.getOrNull()
        if (currentName.isNullOrEmpty()) {
            optionControlClass.getMethod("setName", String::class.java).invoke(optionControl, "降噪控制")
        }
        @Suppress("UNCHECKED_CAST")
        val existing = optionControlClass.getMethod("getListSounds").invoke(optionControl) as? List<Any>
        val devId = runCatching { baseDeviceClass.getMethod("getId").invoke(baseDevice) as? String }.getOrNull()
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

    private fun hookOptionControlManager(
        mgrClassName: String,
        optionControlClass: Class<*>,
        soundItemClass: Class<*>,
    ) {
        val mgrClass = runCatching { classLoader.loadClass(mgrClassName) }.getOrNull() ?: return
        val holderClass = runCatching { classLoader.loadClass("$mgrClassName\$a") }.getOrNull()

        if (holderClass != null) {
            runCatching {
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
                    val result = chain.proceed()
                    val holder = chain.thisObject
                    if (holder != null && soundItem != null) {
                        ensureHolderImageDrawable(holder, holderClass, soundItemClass, soundItem)
                    }
                    result
                }
            }
        }

        runCatching {
            val bManagerMethod = mgrClass.getDeclaredMethod("b", ArrayList::class.java)
                .apply { isAccessible = true }
            module.hook(bManagerMethod).intercept { chain ->
                val list = chain.args.getOrNull(0) as? List<*>
                list?.forEach { item ->
                    if (item != null) fillSoundItemIfMissing(soundItemClass, item)
                }
                chain.proceed()
            }
        }

        val eHooked = listOf(
            arrayOf<Class<*>>(String::class.java, optionControlClass),
            arrayOf<Class<*>>(String::class.java, optionControlClass, Boolean::class.javaPrimitiveType!!),
        ).any { paramTypes ->
            runCatching {
                val eMethod = mgrClass.getDeclaredMethod("e", *paramTypes).apply { isAccessible = true }
                module.hook(eMethod).intercept { chain ->
                    val optionControl = chain.args.getOrNull(1)
                    if (optionControl != null) {
                        fillOptionControlIfNoise(optionControlClass, soundItemClass, optionControl)
                    }
                    chain.proceed()
                }
                true
            }.getOrDefault(false)
        }

        module.log(Log.INFO, TAG, "hooked OptionControlManager $mgrClassName (e=$eHooked)")
    }

    private fun fillOptionControlIfNoise(
        optionControlClass: Class<*>,
        soundItemClass: Class<*>,
        optionControl: Any,
    ) {
        val items = runCatching {
            optionControlClass.getMethod("getListSounds").invoke(optionControl) as? List<*>
        }.getOrNull() ?: return
        val hasNoiseKey = items.any { item ->
            val key = runCatching {
                soundItemClass.getMethod("getSoundKey").invoke(item) as? String
            }.getOrNull()
            key == "key_noice_reduction" || key == "key_auto" || key == "key_transparent"
        }
        if (hasNoiseKey) {
            val name = runCatching {
                optionControlClass.getMethod("getName").invoke(optionControl) as? String
            }.getOrNull()
            if (name.isNullOrEmpty()) {
                optionControlClass.getMethod("setName", String::class.java).invoke(optionControl, "降噪控制")
            }
            items.forEach { item ->
                if (item != null) fillSoundItemIfMissing(soundItemClass, item)
            }
        }
    }

    private fun ensureHolderImageDrawable(
        holder: Any,
        holderClass: Class<*>,
        soundItemClass: Class<*>,
        soundItem: Any,
    ) {
        runCatching {
            val imageView = holderClass.declaredFields
                .firstOrNull { android.widget.ImageView::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(holder) as? android.widget.ImageView
                ?: return
            if (imageView.drawable != null) return

            val key = soundItemClass.getMethod("getSoundKey").invoke(soundItem) as? String ?: return
            val imgNormal = soundItemClass.getMethod("getImgNormal").invoke(soundItem) as? Int ?: 0
            val ctx = imageView.context ?: contextRef ?: return
            val drawable = resolveNoiseDrawable(ctx, key, imgNormal) ?: return
            imageView.setImageDrawable(drawable)
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

    private fun candidateIconNames(key: String?): List<String> = when (key) {
        "key_noice_reduction" -> listOf("ic_noise_reduction", "icon_noise_reduction")
        "key_auto" -> listOf("ic_noise_adaptive", "icon_noise_adaptive", "ic_noise_auto", "icon_noise_auto")
        "key_transparent" -> listOf("ic_noise_trans", "ic_noise_transparent", "icon_noise_trans")
        "key_close" -> listOf("ic_noise_close", "icon_noise_close", "ic_noise_off")
        else -> emptyList()
    }

    private fun noiseIconAndLabel(key: String?): Pair<Int, String>? {
        val label = when (key) {
            "key_noice_reduction" -> "降噪"
            "key_auto" -> "自适应"
            "key_transparent" -> "通透"
            "key_close" -> "关闭"
            else -> return null
        }
        val appCtx = contextRef ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.also { contextRef = it }
        val res = appCtx?.resources
        val pkg = "com.vivo.connbase.connectcenter"
        val twsPkg = "com.android.vivo.tws.vivotws"
        val twsRes = runCatching { appCtx?.packageManager?.getResourcesForApplication(twsPkg) }.getOrNull()
        val names = candidateIconNames(key)
        for (name in names) {
            val ccId = res?.getIdentifier(name, "drawable", pkg)?.takeIf { it != 0 }
            if (ccId != null) return ccId to label
        }
        for (name in names) {
            val twsId = twsRes?.getIdentifier(name, "drawable", twsPkg)?.takeIf { it != 0 }
            if (twsId != null) return twsId to label
        }
        return 0 to label
    }

    private fun resolveNoiseDrawable(ctx: Context, key: String, imgNormal: Int): android.graphics.drawable.Drawable? {
        val names = candidateIconNames(key)
        if (names.isEmpty()) return null
        for (resName in names) {
            val ccId = ctx.resources.getIdentifier(resName, "drawable", "com.vivo.connbase.connectcenter")
            if (ccId != 0) {
                runCatching { ctx.getDrawable(ccId) }.getOrNull()?.let { return it }
            }
        }
        val twsPkg = "com.android.vivo.tws.vivotws"
        val twsRes = runCatching { ctx.packageManager.getResourcesForApplication(twsPkg) }.getOrNull()
        if (twsRes != null) {
            for (resName in names) {
                val twsId = twsRes.getIdentifier(resName, "drawable", twsPkg)
                if (twsId != 0) {
                    runCatching { twsRes.getDrawable(twsId, null) }.getOrNull()?.let { return it }
                }
            }
            if (imgNormal > 0) {
                runCatching { twsRes.getDrawable(imgNormal, null) }.getOrNull()?.let { return it }
            }
        }
        return createFallbackNoiseDrawable(key, ctx.resources.displayMetrics.density)
    }

    private fun createFallbackNoiseDrawable(key: String, density: Float): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.8f * density
                strokeCap = android.graphics.Paint.Cap.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                if (b.isEmpty) return
                val cx = b.exactCenterX()
                val cy = b.exactCenterY()
                val r = minOf(b.width(), b.height()) * 0.34f
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawCircle(cx, cy, r, paint)
                when (key) {
                    "key_noice_reduction" -> {
                        paint.style = android.graphics.Paint.Style.FILL
                        canvas.drawCircle(cx, cy, r * 0.42f, paint)
                    }
                    "key_auto" -> {
                        paint.style = android.graphics.Paint.Style.STROKE
                        canvas.drawArc(
                            cx - r * 0.52f, cy - r * 0.52f, cx + r * 0.52f, cy + r * 0.52f,
                            -45f, 180f, false, paint,
                        )
                    }
                    "key_transparent" -> {
                        paint.style = android.graphics.Paint.Style.STROKE
                        canvas.drawCircle(cx, cy, r * 0.45f, paint)
                    }
                    "key_close" -> {
                        val d = r * 0.68f
                        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint)
                    }
                }
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun fillSoundItemIfMissing(soundItemClass: Class<*>, item: Any) {
        runCatching {
            val key = soundItemClass.getMethod("getSoundKey").invoke(item) as? String
            val (icon, label) = noiseIconAndLabel(key) ?: return
            val imgNormal = soundItemClass.getMethod("getImgNormal").invoke(item) as? Int ?: 0
            if (icon > 0 && imgNormal <= 0) {
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
