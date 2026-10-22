package com.openbrowser.mobile.web

import android.content.Context
import com.openbrowser.mobile.data.Profile
import java.io.File
import java.util.Locale

/**
 * 清除单个环境的 WebView 数据（Cookie / localStorage / IndexedDB / 缓存）。
 *
 * 清理时必须保证该环境没有被打开：WebView 正在运行时删除目录可能导致其崩溃。
 */
object WebDataCleaner {

    fun clearProfile(context: Context, profile: Profile) {
        val lease = com.openbrowser.mobile.sync.ProfileLeases.acquire(context, listOf(profile.id)) ?: error("请先关闭该环境，或等待同步 / Cookie 快照结束")
        lease.use { clearProfileUnderLease(context, profile); GeckoDataCleanup.request(context, profile) }
    }
    internal fun clearProfileUnderLease(context: Context, profile: Profile) {
        WebViewProcess.stopForCleanup(context, profile.dataSuffix)
        com.openbrowser.mobile.sync.CookieSnapshots(context).clear(profile.id)
        com.openbrowser.mobile.sync.WebsiteSnapshots(context, profile.id).clearSites()
        val suffix = profile.dataSuffix
        val dataDir = File(context.applicationInfo.dataDir)
        val targets = listOf(
            File(dataDir, "app_webview/$suffix"),
            File(dataDir, "app_webview_$suffix"),
            File(context.cacheDir, "WebView/$suffix"),
            File(dataDir, "code_cache/WebView/$suffix"),
        )
        targets.forEach { dir ->
            check(!dir.exists() || dir.deleteRecursively()) { "网站数据清理失败，请稍后重试" }
        }
        com.openbrowser.mobile.sync.SyncScheduler.kick(context)
    }

    fun clearAll(context: Context) {
        val profiles = com.openbrowser.mobile.data.ProfileStore(context).allStrict() + com.openbrowser.mobile.data.TrashProfileStore(context).all().map { it.profile }
        val lease = com.openbrowser.mobile.sync.ProfileLeases.acquire(context, profiles.map { it.id }) ?: error("请先关闭所有浏览器环境")
        lease.use { clearAllUnderLease(context) }
    }
    internal fun clearAllUnderLease(context: Context) {
        WebViewProcess.stopForCleanup(context, null)
        com.openbrowser.mobile.sync.CookieSnapshots(context).clearAll()
        (com.openbrowser.mobile.data.ProfileStore(context).allStrict() + com.openbrowser.mobile.data.TrashProfileStore(context).all().map { it.profile }).forEach { com.openbrowser.mobile.sync.WebsiteSnapshots(context, it.id).clearSites() }
        val dataDir = File(context.applicationInfo.dataDir)
        val directories = dataDir.listFiles()?.filter { it.name == "app_webview" || it.name.startsWith("app_webview_") }.orEmpty() + File(context.cacheDir, "WebView")
        directories.forEach { check(!it.exists() || it.deleteRecursively()) { "网站数据清理失败，请稍后重试" } }
    }

    fun totalSize(context: Context): String {
        val dataDir = File(context.applicationInfo.dataDir)
        val total = dataDir.listFiles()?.filter { it.name == "app_webview" || it.name.startsWith("app_webview_") }?.sumOf { dirSize(it) }.orZero() + dirSize(File(context.cacheDir, "WebView"))
        return formatSize(total)
    }

    fun sizeLabel(context: Context, profile: Profile): String {
        val suffix = profile.dataSuffix
        val dataDir = File(context.applicationInfo.dataDir)
        val total = listOf(
            File(dataDir, "app_webview/$suffix"),
            File(dataDir, "app_webview_$suffix"),
        ).filter { it.exists() }.sumOf { dirSize(it) }
        return formatSize(total)
    }

    private fun dirSize(dir: File): Long = runCatching {
        dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }.getOrDefault(0L)
    private fun Long?.orZero(): Long = this ?: 0L

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
