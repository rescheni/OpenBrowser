package com.openbrowser.mobile.web

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import com.openbrowser.mobile.ui.RelaunchReceiver

/**
 * 切换环境时 `:browser` 进程需要以新的 WebView 数据目录后缀重启，
 * 这里通过 AlarmManager + 广播在进程退出后重新拉起浏览器 Activity。
 */
object ProcessRelauncher {

    private const val ACTION_RELAUNCH = "com.openbrowser.mobile.action.RELAUNCH_BROWSER"
    private const val REQUEST_CODE = 4271

    fun schedule(context: Context, target: Intent) {
        val ctx = context.applicationContext
        val pending = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            Intent(ACTION_RELAUNCH)
                .setClass(ctx, RelaunchReceiver::class.java)
                .putExtra(RelaunchReceiver.EXTRA_TARGET, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = ctx.getSystemService(AlarmManager::class.java) ?: return
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 350, pending)
    }

    fun killCurrentProcess() {
        Process.killProcess(Process.myPid())
    }
}
