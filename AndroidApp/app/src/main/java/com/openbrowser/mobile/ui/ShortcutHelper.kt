package com.openbrowser.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.openbrowser.mobile.R
import com.openbrowser.mobile.data.Profile

/**
 * 把环境固定到桌面。
 *
 * 优先使用 Android 8.0 的 `requestPinShortcut`；部分国产桌面不实现该接口，
 * 此时退回旧的 INSTALL_SHORTCUT 广播（需要清单里的 launcher 权限）。
 * 两种方式都指向 [ShortcutLauncherActivity] 这个中转页，由它再打开 [BrowserActivity]，
 * 这样快捷方式不需要 BrowserActivity 对外导出。
 *
 * 同一个环境固定时复用同一个快捷方式 ID，所以重复「添加到桌面」只会有一个图标；
 * 删除环境时用 [removePins] 尽力撤回图标。
 */
object ShortcutHelper {

    private const val ACTION_OPEN_PROFILE = "com.openbrowser.mobile.action.OPEN_PROFILE"
    private const val LEGACY_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
    private const val LEGACY_UNINSTALL_SHORTCUT = "com.android.launcher.action.UNINSTALL_SHORTCUT"

    enum class Result {
        /** 系统已弹出“添加到桌面”确认。 */
        REQUESTED,

        /** 桌面不支持官方接口，已发送旧版广播。 */
        FALLBACK,

        /** 两种方式都失败了。 */
        FAILED,
    }

    fun requestPin(context: Context, profile: Profile): Result {
        val label = profile.name.ifBlank { context.getString(R.string.app_name) }
        val launchIntent = ShortcutLauncherActivity.intent(context, profile.id)
            .setAction(ACTION_OPEN_PROFILE)

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(profile.id))
                .setShortLabel(label)
                .setLongLabel(context.getString(R.string.shortcut_long_label, label))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(launchIntent)
                // 标记为长驻快捷方式，删除环境时才有机会通过系统接口撤回桌面图标
                .setLongLived(true)
                .build()
            val ok = runCatching {
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
            }.getOrDefault(false)
            return if (ok) Result.REQUESTED else Result.FAILED
        }

        return if (sendLegacyInstall(context, launchIntent, label)) {
            Result.FALLBACK
        } else {
            Result.FAILED
        }
    }

    /**
     * 删除环境时同步移除桌面上的快捷方式。
     *
     * 注意：桌面图标归用户所有，系统不保证应用能删掉它。这里做两件尽力而为的事：
     * 一是对长驻快捷方式调用 [ShortcutManagerCompat.removeLongLivedShortcuts]；
     * 二是对旧版广播装出来的图标再发一次 UNINSTALL_SHORTCUT。
     * 若都未生效，图标会留下，点开时由 [ShortcutLauncherActivity] 提示环境已删除。
     */
    fun removePin(context: Context, profile: Profile) = removePins(context, listOf(profile))

    fun removePins(context: Context, profiles: List<Profile>) {
        if (profiles.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ids = profiles.map { shortcutId(it.id) }
            runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, ids) }
        }
        runCatching {
            profiles.forEach { context.sendBroadcast(legacyUninstall(context, it)) }
        }
        Unit
    }

    @Suppress("DEPRECATION")
    private fun sendLegacyInstall(
        context: Context,
        launchIntent: Intent,
        label: String,
    ): Boolean = runCatching {
        val intent = Intent(LEGACY_INSTALL_SHORTCUT).apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
            putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(context, R.mipmap.ic_launcher),
            )
            putExtra("duplicate", false)
        }
        context.sendBroadcast(intent)
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun legacyUninstall(context: Context, profile: Profile): Intent = Intent(LEGACY_UNINSTALL_SHORTCUT).apply {
        // 桌面按 EXTRA_SHORTCUT_INTENT 匹配已有图标，必须和安装时用的是同一个 Intent
        putExtra(
            Intent.EXTRA_SHORTCUT_INTENT,
            ShortcutLauncherActivity.intent(context, profile.id).setAction(ACTION_OPEN_PROFILE),
        )
        putExtra(Intent.EXTRA_SHORTCUT_NAME, profile.name.ifBlank { context.getString(R.string.app_name) })
    }

    private fun shortcutId(profileId: String): String = "profile_$profileId"
}
