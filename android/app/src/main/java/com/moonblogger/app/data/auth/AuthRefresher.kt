package com.moonblogger.app.data.auth

import com.moonblogger.app.data.model.RefreshRequest
import com.moonblogger.app.data.remote.AuthApi
import com.moonblogger.app.data.token.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordina el refresco del access token con "single-flight": ante múltiples
 * peticiones 401 simultáneas solo se lanza UNA llamada a /auth/refresh/ y el
 * resto espera al mismo resultado.
 *
 * El refresh se ROTA en el backend (SimpleJWT): la respuesta trae un refresh
 * nuevo, que se persiste aquí mismo.
 *
 * Si el refresh falla se devuelve false; la decisión de cerrar sesión la toma
 * el llamador (ver [com.moonblogger.app.network.TokenAuthenticator] y
 * [SessionManager]).
 */
class AuthRefresher(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
    private val scope: CoroutineScope,
) {

    private val lock = Mutex()
    private var inFlight: Deferred<Boolean>? = null

    /**
     * Devuelve true si el access token se ha renovado (y persistido).
     * Concurrente-seguro: todas las llamadas simultáneas comparten un único
     * refresh en curso.
     */
    suspend fun refreshAccessToken(): Boolean {
        val deferred = lock.withLock {
            inFlight ?: scope.async { doRefresh() }.also { inFlight = it }
        }
        return try {
            deferred.await()
        } finally {
            lock.withLock {
                if (inFlight === deferred) inFlight = null
            }
        }
    }

    private suspend fun doRefresh(): Boolean {
        val refreshToken = tokenStore.refreshToken ?: return false
        return try {
            val response = authApi.refresh(RefreshRequest(refreshToken))
            tokenStore.saveTokens(response.access, response.refresh)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
