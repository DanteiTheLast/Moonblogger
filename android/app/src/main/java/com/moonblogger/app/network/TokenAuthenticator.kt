package com.moonblogger.app.network

import com.moonblogger.app.data.auth.AuthRefresher
import com.moonblogger.app.data.auth.SessionManager
import com.moonblogger.app.data.token.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Authenticator de OkHttp: cuando una petición autenticada responde 401
 * (access caducado):
 *
 *  1. Hace refresh con single-flight ([AuthRefresher]) — si hay varios 401
 *     simultáneos, solo se lanza un refresh.
 *  2. Persiste el refresh ROTADO que devuelve el backend.
 *  3. Reintenta la petición original UNA vez con el nuevo access.
 *  4. Si el refresh falla → logout local (borrar tokens) y no reintenta.
 *
 * Protecciones anti-bucle:
 *  - Las rutas de auth (login/refresh) no se reintentan: si /auth/refresh/
 *    responde 401 (refresh inválido), se hace logout y se devuelve null.
 *  - La petición reintentada lleva un header de marcado; si vuelve a dar 401,
 *    se devuelve null (un solo reintento).
 */
class TokenAuthenticator(
    private val authRefresher: AuthRefresher,
    private val sessionManager: SessionManager,
    private val tokenStore: TokenStore,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.code != 401) return null

        val path = response.request.url.encodedPath

        // El refresh devolvió 401 (refresh inválido/caducado): la sesión no se
        // puede renovar. Logout local y stop (evita reintentar el refresh).
        if (path.endsWith("/auth/refresh/")) {
            sessionManager.logout()
            return null
        }

        // Login con credenciales incorrectas (401 del login): no se refresca
        // ni se cierra sesión; el error lo muestra la pantalla de login.
        if (path.endsWith("/auth/login/")) {
            return null
        }

        // Ya se reintentó una vez: no hay más reintentos.
        if (response.request.header(RETRY_HEADER) != null) {
            return null
        }

        // El refresh se ejecuta en el hilo de OkHttp; single-flight hace que
        // los 401 concurrentes compartan la misma llamada de refresh.
        val refreshed = runBlocking { authRefresher.refreshAccessToken() }
        if (!refreshed) {
            sessionManager.logout()
            return null
        }

        val newAccess = tokenStore.accessToken ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .header(RETRY_HEADER, "1")
            .build()
    }

    private companion object {
        const val RETRY_HEADER = "X-MoonBlogger-Auth-Retry"
    }
}
