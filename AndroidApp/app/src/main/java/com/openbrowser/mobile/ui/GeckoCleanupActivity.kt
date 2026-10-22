package com.openbrowser.mobile.ui

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoResult

/** 无界面清理入口，运行在 :gecko 进程中。 */
class GeckoCleanupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contextId = intent.getStringExtra(EXTRA_CONTEXT_ID)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (contextId.isNullOrBlank() || profileId.isNullOrBlank()) {
            finish()
            return
        }
        val app = applicationContext
        Thread {
            var lease: com.openbrowser.mobile.sync.ProfileLeases? = null
            var attempts = 0
            while (lease == null && attempts++ < 100) {
                lease = com.openbrowser.mobile.sync.ProfileLeases.acquire(app, listOf(profileId))
                if (lease == null) Thread.sleep(100)
            }
            val acquired = lease ?: return@Thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    GeckoRuntimeHolder.get(app).storageController.clearDataForSessionContext(contextId).accept({
                        runCatching { com.openbrowser.mobile.web.GeckoDataCleanup.pending(app, profileId).delete() }
                        acquired.close()
                    }, {
                        acquired.close()
                        android.util.Log.w("GeckoCleanup", "Context cleanup failed; pending marker retained")
                    })
                }.onFailure { acquired.close() }
            }
        }.start()
        // NoDisplay Activity 必须在 onCreate 完成时退出；原生结果持有租约直至真正完成。
        finish()
    }

    companion object {
        const val EXTRA_CONTEXT_ID = "com.openbrowser.mobile.extra.GECKO_CONTEXT_ID"
        const val EXTRA_PROFILE_ID = "com.openbrowser.mobile.extra.PROFILE_ID"
    }
}
