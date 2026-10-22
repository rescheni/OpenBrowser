package com.openbrowser.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.openbrowser.mobile.R
import com.openbrowser.mobile.data.ProfileStore

/**
 * 桌面快捷方式的透明中转页：拿到环境 ID 后转交给 [BrowserActivity] 并立即结束自己。
 *
 * 该页面必须导出，桌面才能启动它；它本身不展示任何界面，也不接受外部传入的敏感数据。
 * 如果环境已经被删除（桌面图标没能自动移除），这里给出提示而不是打开空白页。
 */
class ShortcutLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra(BrowserActivity.EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }
        val profile = ProfileStore(this).get(profileId)
        if (profile == null) {
            Toast.makeText(this, R.string.shortcut_profile_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        BrowserLauncher.open(this, profile)
        finish()
    }

    companion object {
        fun intent(context: Context, profileId: String): Intent =
            Intent(context, ShortcutLauncherActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_PROFILE_ID, profileId)
    }
}
