package com.openbrowser.mobile.ui

import android.content.Context
import com.openbrowser.mobile.data.BrowserEngine
import com.openbrowser.mobile.data.Profile

/** 唯一的浏览器入口。以后加入第三种内核时只需扩展这里。 */
object BrowserLauncher {

    fun open(context: Context, profile: Profile) {
        val intent = when (BrowserEngine.from(profile.engine)) {
            BrowserEngine.GECKO -> GeckoBrowserActivity.intent(context, profile.id)
            BrowserEngine.WEBVIEW -> BrowserActivity.intent(context, profile.id)
        }
        context.startActivity(intent)
    }
}
