package com.openbrowser.mobile.web

import android.content.Context
import com.openbrowser.mobile.data.BookmarkStore
import com.openbrowser.mobile.data.DownloadStore
import com.openbrowser.mobile.data.HistoryStore
import com.openbrowser.mobile.data.Profile

/** 环境彻底删除时的统一清理入口。回收站移入阶段不要调用它，以保留登录态。 */
object ProfileDataCleaner {

    fun clear(context: Context, profile: Profile) {
        val lease = com.openbrowser.mobile.sync.ProfileLeases.acquire(context, listOf(profile.id)) ?: error("请先关闭该环境，或等待同步结束")
        lease.use { clearUnderLease(context, profile) }
    }
    internal fun clearUnderLease(context: Context, profile: Profile) {
        val appContext = context.applicationContext
        WebDataCleaner.clearProfileUnderLease(appContext, profile)
        GeckoDataCleanup.request(appContext, profile)
        com.openbrowser.mobile.sync.WebsiteSnapshots(appContext, profile.id).clear()
        HistoryStore(appContext).clear(profile.id)
        BookmarkStore(appContext).clear(profile.id)
        DownloadStore(appContext).clear(profile.id)
    }
}
