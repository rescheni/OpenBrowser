package com.openbrowser.mobile

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** 主题偏好：跟随系统 / 浅色 / 深色。 */
object ThemePrefs {

    private const val PREFS = "openbrowser_prefs"
    private const val KEY_THEME = "theme_mode"

    fun current(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    fun save(context: Context, mode: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME, mode)
            .apply()
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(current(context))
    }
}
