package com.moonblogger.app.data.auth

import com.moonblogger.app.core.JwtDecoder
import com.moonblogger.app.data.token.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado de sesión de la aplicación. */
sealed interface SessionState {
    /** Comprobando tokens al arrancar. */
    data object Loading : SessionState

    /** Sin sesión: hay que ir al login. */
    data object LoggedOut : SessionState

    /** Sesión válida: se muestran los posts. */
    data object LoggedIn : SessionState
}

/**
 * Fuente de verdad de la sesión. Se encarga de:
 *  - Inicializar la sesión al arrancar: si hay tokens y el access está
 *    caducado, intenta refrescarlo antes de mostrar el login.
 *  - Registrar el login correcto.
 *  - Cerrar sesión (solo local: borra tokens; no existe endpoint de logout).
 */
class SessionManager(
    private val tokenStore: TokenStore,
    private val authRefresher: AuthRefresher,
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    suspend fun initialize() {
        _state.value = SessionState.Loading

        if (!tokenStore.hasTokens()) {
            _state.value = SessionState.LoggedOut
            return
        }

        val access = tokenStore.accessToken
        if (access != null && JwtDecoder.isExpired(access)) {
            authRefresher.refreshAccessToken()
            // Invalid refresh clears its own credentials; transient failures retain them.
            // Read current tokens below, since login/logout may happen during the request.
        }

        synchronized(tokenStore) {
            _state.value = if (tokenStore.hasTokens()) SessionState.LoggedIn else SessionState.LoggedOut
        }
    }

    fun synchronizeInvalidation() = synchronized(tokenStore) {
        if (!tokenStore.hasTokens()) _state.value = SessionState.LoggedOut
    }

    fun onLoginSuccess(access: String, refresh: String) = synchronized(tokenStore) {
        tokenStore.saveTokens(access, refresh)
        _state.value = SessionState.LoggedIn
    }

    fun logout() = synchronized(tokenStore) {
        tokenStore.clear()
        _state.value = SessionState.LoggedOut
    }
}
