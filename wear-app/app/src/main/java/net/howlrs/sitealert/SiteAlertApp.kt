package net.howlrs.sitealert

import android.app.Application

class SiteAlertApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlertNotifier.createChannels(this)
    }
}
