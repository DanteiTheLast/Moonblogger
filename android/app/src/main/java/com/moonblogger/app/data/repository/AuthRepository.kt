package com.moonblogger.app.data.repository

import com.moonblogger.app.data.model.LoginRequest
import com.moonblogger.app.data.model.TokenResponse
import com.moonblogger.app.data.remote.AuthApi
import com.moonblogger.app.data.remote.safeApiCall

/**
 * Acceso a la autenticación. El guardado de tokens lo decide el llamador
 * (normalmente [com.moonblogger.app.data.auth.SessionManager]).
 */
class AuthRepository(private val authApi: AuthApi) {

    suspend fun login(username: String, password: String): Result<TokenResponse> =
        safeApiCall { authApi.login(LoginRequest(username, password)) }
}
