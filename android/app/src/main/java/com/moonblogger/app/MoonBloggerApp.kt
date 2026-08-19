package com.moonblogger.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.moonblogger.app.di.AppContainer

class MoonBloggerApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader = container.imageLoader
}
