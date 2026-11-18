package com.originpods.app.hook

import android.util.Log
import com.originpods.app.BuildConfig
import com.originpods.integration.ControlAppCatalog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class OriginPodsXposedEntry : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        log(
            Log.INFO,
            TAG,
            "onPackageReady pkg=${param.packageName} first=${param.isFirstPackage} v=${BuildConfig.VERSION_NAME}",
        )
        if (!param.isFirstPackage) return

        val hooks: List<ProcessHook> = when (param.packageName) {
            PKG_BLUETOOTH -> listOf(BluetoothProcessHook(this, param.classLoader))
            PKG_SETTINGS -> listOf(SettingsHook(this, param.classLoader))
            PKG_VIVO_TWS -> listOf(VivoTwsHook(this, param.classLoader))
            PKG_CONNECT_CENTER -> listOf(ConnectCenterHook(this, param.classLoader))
            in ControlAppCatalog.all.map { it.packageName } -> listOf(
                ControlAppScopeHook(this, param.packageName),
            )
            else -> emptyList()
        }

        hooks.forEach { hook ->
            runCatching { hook.install() }
                .onSuccess {
                    log(Log.INFO, TAG, "installed ${hook.javaClass.simpleName} in ${param.packageName}")
                }
                .onFailure { err ->
                    log(Log.ERROR, TAG, "failed ${hook.javaClass.simpleName} in ${param.packageName}", err)
                }
        }
    }

    companion object {
        private const val TAG = "OriginPods"
        const val PKG_BLUETOOTH = "com.android.bluetooth"
        const val PKG_SETTINGS = "com.android.settings"
        const val PKG_VIVO_TWS = "com.android.vivo.tws.vivotws"
        const val PKG_CONNECT_CENTER = "com.vivo.connbase.connectcenter"
        const val PKG_CONNECT_CENTER_SYSUI = "com.vivo.connbase.sysui"
        const val ACTION_STATE_CHANGED = "com.originpods.app.ACTION_STATE_CHANGED"
        const val ACTION_CONTROL = "com.originpods.app.ACTION_CONTROL"
    }
}

interface ProcessHook {
    fun install()
}
