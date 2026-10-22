package com.openbrowser.mobile.ui

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/** Gecko 进程内共享一个 Runtime，浏览器 Activity 与清理 Activity 共用它。 */
object GeckoRuntimeHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    @Synchronized
    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        return GeckoRuntime.create(
            context.applicationContext,
            GeckoRuntimeSettings.Builder().build(),
        ).also { runtime = it }
    }
}
