package com.moonblogger.app.di

import com.moonblogger.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Logging de red: solo se añade en debug (nivel BASIC: método, URL y código
 * de respuesta). En release la intercepción no se registra aunque la clase
 * esté en el classpath.
 */
internal fun OkHttpClient.Builder.maybeAddLogging(): OkHttpClient.Builder {
    if (BuildConfig.DEBUG) {
        addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
    }
    return this
}
