package com.openbrowser.mobile

import android.content.Context

/** 界面偏好：地址栏位置等与主题无关的显示选项。 */
object UiPrefs {

    private const val PREFS = "openbrowser_prefs"
    private const val KEY_ADDRESS_BAR_BOTTOM = "address_bar_bottom"

    /** 地址栏是否放在浏览器页面底部（默认顶部）。 */
    fun addressBarBottom(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADDRESS_BAR_BOTTOM, false)

    fun saveAddressBarBottom(context: Context, bottom: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADDRESS_BAR_BOTTOM, bottom).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
