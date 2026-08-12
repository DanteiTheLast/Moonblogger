package com.moonblogger.app.data.remote

import com.moonblogger.app.data.model.LoginRequest
import com.moonblogger.app.data.model.RefreshRequest
import com.moonblogger.app.data.model.TokenResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Endpoints públicos de autenticación JWT (SimpleJWT, rotación activa).
 * El refresh se rota: la respuesta devuelve un refresh NUEVO que debe
 * persistirse.
 */
interface AuthApi {

    @POST("api/v1/auth/login/")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("api/v1/auth/refresh/")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse
}
