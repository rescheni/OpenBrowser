package com.openbrowser.mobile.web

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 记录当前浏览器进程实际使用的 WebView 数据目录后缀。
 *
 * `WebView.setDataDirectorySuffix` 每个进程只能调用一次，因此切换环境时
 * 需要 kill 掉 `:browser` 进程再重新拉起（见 ProcessRelauncher）。
 */
object WebViewProcess {
    @Volatile
    var appliedSuffix: String? = null

    fun record(context: Context, suffix: String) = locked(context) {
        val file = AtomicFile(File(context.filesDir, "webview-process.json"))
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("pid", Process.myPid()).put("suffix", suffix).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }

    /** Activity 已关闭后 WebView 进程仍可能持有数据库句柄，删目录前先停止这个空闲进程。 */
    fun stopForCleanup(context: Context, suffix: String?) = locked(context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: error("无法确认浏览器进程状态")
        val processes = manager.runningAppProcesses ?: error("无法确认浏览器进程状态")
        val browser = processes.firstOrNull { it.processName == context.packageName + ":browser" } ?: return@locked
        val registered = AtomicFile(File(context.filesDir, "webview-process.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
        check(registered.getInt("pid") == browser.pid) { "浏览器进程正在切换，请稍后重试清理" }
        if (suffix != null && registered.getString("suffix") != suffix) return@locked
        check(browser.pid != Process.myPid()) { "请先退出该浏览器环境" }
        Process.killProcess(browser.pid)
        var attempts = 0
        while (manager.runningAppProcesses?.any { it.pid == browser.pid } == true && attempts++ < 30) Thread.sleep(10)
        check(manager.runningAppProcesses?.none { it.pid == browser.pid } == true) { "浏览器进程尚未停止，请稍后重试清理" }
    }
    private val lock = Any()
    private fun <T> locked(context: Context, block: () -> T): T = synchronized(lock) {
        RandomAccessFile(File(context.filesDir, "webview-process.lock"), "rw").use { it.channel.lock().use { block() } }
    }
}
