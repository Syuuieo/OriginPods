package com.originpods.app.hook

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * Tracks official companion app (`HeyMelody` / `Wireless Earphones`) lifecycle
 * so OriginPods can yield private RFCOMM channels when the user opens the official app.
 */
class ControlAppScopeHook(
    private val module: XposedModule,
    private val packageName: String,
) : ProcessHook {

    override fun install() {
        val onCreate = runCatching {
            Application::class.java.getDeclaredMethod("onCreate")
        }.getOrNull() ?: return

        module.hook(onCreate).intercept { chain ->
            Log.i(TAG, "official companion app active: $packageName")
            chain.proceed()
        }
    }

    companion object {
        private const val TAG = "OriginPods.Yield"
    }
}
