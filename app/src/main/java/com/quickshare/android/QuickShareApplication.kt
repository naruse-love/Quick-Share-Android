package com.quickshare.android

import android.app.Application
import com.quickshare.android.di.AppContainer
import com.quickshare.android.di.DefaultAppContainer
import com.quickshare.android.util.NotificationHelper

class QuickShareApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContainer = DefaultAppContainer(this)
        NotificationHelper.createNotificationChannel(this)
    }

    companion object {
        lateinit var instance: QuickShareApplication
            private set
    }
}
