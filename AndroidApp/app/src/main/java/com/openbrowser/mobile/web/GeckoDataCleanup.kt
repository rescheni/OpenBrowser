package com.openbrowser.mobile.web

import android.content.Context
import android.content.Intent
import com.openbrowser.mobile.data.Profile
import com.openbrowser.mobile.ui.GeckoCleanupActivity
import java.io.File

/** 把 Gecko context 数据清理请求转交到 :gecko 进程。 */
object GeckoDataCleanup {
    fun pending(context: Context, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return File(File(context.applicationContext.filesDir, "gecko_cleanup_pending").apply { mkdirs() }, id)
    }
    fun request(context: Context, profile: Profile) {
        pending(context, profile.id).writeText(profile.dataSuffix)
        runCatching {
            context.startActivity(
                Intent(context, GeckoCleanupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(GeckoCleanupActivity.EXTRA_CONTEXT_ID, profile.dataSuffix)
                    .putExtra(GeckoCleanupActivity.EXTRA_PROFILE_ID, profile.id),
            )
        }
    }
}
