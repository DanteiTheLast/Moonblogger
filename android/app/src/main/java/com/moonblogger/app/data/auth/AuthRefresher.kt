package com.moonblogger.app.data.auth

import com.moonblogger.app.data.model.RefreshRequest
import com.moonblogger.app.data.remote.AuthApi
import com.moonblogger.app.data.token.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** A network failure is recoverable; only a rejected refresh invalidates the session. */
enum class RefreshResult { SUCCESS, INVALID, TRANSIENT, SESSION_CHANGED }

class AuthRefresher(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var inFlight: Deferred<RefreshResult>? = null

    suspend fun refreshAccessToken(): RefreshResult {
        val deferred = lock.withLock {
            inFlight?.takeUnless { it.isCompleted } ?: scope.async { doRefresh() }.also { inFlight = it }
        }
        return try {
            deferred.await()
        } finally {
            lock.withLock { if (inFlight === deferred && deferred.isCompleted) inFlight = null }
        }
    }

    private suspend fun doRefresh(): RefreshResult {
        val refreshToken = synchronized(tokenStore) { tokenStore.refreshToken }
            ?: return RefreshResult.SESSION_CHANGED
        return try {
            val response = authApi.refresh(RefreshRequest(refreshToken))
            synchronized(tokenStore) {
                if (tokenStore.refreshToken != refreshToken) {
                    RefreshResult.SESSION_CHANGED
                } else {
                    tokenStore.saveTokens(response.access, response.refresh)
                    RefreshResult.SUCCESS
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // SimpleJWT rejects an expired, blacklisted or invalid refresh with 401.
            // 400, 429, 5xx and transport errors are not proof of invalid credentials.
            if (e is HttpException && e.code() == 401) {
                synchronized(tokenStore) {
                    if (tokenStore.refreshToken != refreshToken) RefreshResult.SESSION_CHANGED
                    else {
                        tokenStore.clear()
                        RefreshResult.INVALID
                    }
                }
            } else RefreshResult.TRANSIENT
        }
    }
}
