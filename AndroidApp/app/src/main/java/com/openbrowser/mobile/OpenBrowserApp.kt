package com.openbrowser.mobile

import android.app.Application

class OpenBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ThemePrefs.apply(this)
        if (Application.getProcessName() == packageName) com.openbrowser.mobile.sync.SyncScheduler.kick(this)
    }
}
