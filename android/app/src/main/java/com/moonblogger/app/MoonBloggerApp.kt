package com.moonblogger.app

import android.app.Application
import com.moonblogger.app.di.AppContainer

class MoonBloggerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
