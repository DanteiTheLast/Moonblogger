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
            val refreshed = authRefresher.refreshAccessToken()
            if (!refreshed) {
                // El refresh falló (token inválido/caducado): sesión local muerta.
                tokenStore.clear()
                _state.value = SessionState.LoggedOut
                return
            }
        }

        _state.value = SessionState.LoggedIn
    }

    fun onLoginSuccess(access: String, refresh: String) {
        tokenStore.saveTokens(access, refresh)
        _state.value = SessionState.LoggedIn
    }

    fun logout() {
        tokenStore.clear()
        _state.value = SessionState.LoggedOut
    }
}
