package com.openbrowser.mobile.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val result = SyncCoordinator(applicationContext).run { isStopped }
        return if (result.status in listOf("error", "busy") && runAttemptCount < 5 && !result.message.contains(Regex("HTTP 40[013]|密码|空间"))) Result.retry() else Result.success()
    }
}

object SyncScheduler {
    fun kick(context: Context) {
        // Cookie 快照可能在 :browser 进程生成，统一由主进程调度 WorkManager。
        context.applicationContext.sendBroadcast(Intent(context, SyncKickReceiver::class.java))
    }
    fun configure(context: Context) {
        val config = SyncStore(context).read().getJSONObject("config")
        val manager = WorkManager.getInstance(context)
        if (!config.optBoolean("enabled")) { manager.cancelUniqueWork("portable-sync-periodic"); manager.cancelUniqueWork("portable-sync-change"); return }
        val constraints = Constraints.Builder().apply { if (config.optString("provider") != "local") setRequiredNetworkType(if (config.optBoolean("wifiOnly")) NetworkType.UNMETERED else NetworkType.CONNECTED) }.build()
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(config.optInt("interval", 15).coerceIn(15, 1440).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("portable-sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, periodic)
        val once = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).setInitialDelay(3, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork("portable-sync-change", ExistingWorkPolicy.KEEP, once)
    }
}
class SyncKickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread { try { runCatching { SyncScheduler.configure(context.applicationContext) } } finally { pending.finish() } }.start()
    }
}
