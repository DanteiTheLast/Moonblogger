package com.moonblogger.app.network

import com.moonblogger.app.data.token.TokenStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Añade `Authorization: Bearer <access>` a las peticiones que lo requieren.
 * Los endpoints públicos de auth (login/refresh) NO llevan el header.
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenStore.accessToken

        val request = if (!original.isAuthEndpoint() && token != null) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}

internal fun okhttp3.Request.isAuthEndpoint(): Boolean {
    val path = url.encodedPath
    return path.endsWith("/auth/login/") || path.endsWith("/auth/refresh/")
}
