package com.openbrowser.mobile.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat

/** 收到重启广播后，用新的数据目录重新打开浏览器界面。 */
class RelaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java) ?: return
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { context.startActivity(target) }
    }

    companion object {
        const val EXTRA_TARGET = "com.openbrowser.mobile.extra.TARGET"
    }
}
