package com.originpods.app.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.originpods.integration.EarbudAdapterRegistry
import com.originpods.integration.EarbudIdentity
import com.originpods.integration.StandardEarbudAdapter
import io.github.libxposed.api.XposedModule

/**
 * Hooks `com.android.settings` (`VivoBluetoothUtils`) so OPPO/OnePlus earbuds are recognized
 * as native vivo TWS devices in Bluetooth Settings and trigger VivoTws integration.
 */
@SuppressLint("MissingPermission")
class SettingsHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) : ProcessHook {

    override fun install() {
        val utilsClass = runCatching {
            classLoader.loadClass("com.vivo.settings.bluetooth.utils.VivoBluetoothUtils")
        }.getOrNull() ?: return

        utilsClass.declaredMethods
            .filter { it.name == "isVivoEarphone" }
            .forEach { method ->
                runCatching {
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (result == true) return@intercept true
                        val device = chain.args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                            ?: return@intercept result
                        if (isSupportedOppoPod(device)) {
                            module.log(Log.INFO, TAG, "spoofed isVivoEarphone=true for ${device.name} (${device.address})")
                            return@intercept true
                        }
                        result
                    }
                }
            }
        module.log(Log.INFO, TAG, "hooked VivoBluetoothUtils.isVivoEarphone")
    }

    private fun isSupportedOppoPod(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull() ?: return false
        val adapter = EarbudAdapterRegistry.resolve(
            EarbudIdentity(address = device.address, deviceName = name),
        ) ?: return false
        return adapter.id != StandardEarbudAdapter.ID
    }

    companion object {
        private const val TAG = "OriginPods.Settings"
    }
}
