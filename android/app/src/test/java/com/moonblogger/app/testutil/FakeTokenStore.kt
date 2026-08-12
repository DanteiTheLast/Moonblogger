package com.moonblogger.app.testutil

import com.moonblogger.app.data.token.TokenStore

/** TokenStore en memoria para tests JVM (sin Android). */
class FakeTokenStore : TokenStore {

    override var accessToken: String? = null
    override var refreshToken: String? = null

    override fun saveTokens(access: String, refresh: String) {
        accessToken = access
        refreshToken = refresh
    }

    override fun clear() {
        accessToken = null
        refreshToken = null
    }

    override fun hasTokens(): Boolean = accessToken != null && refreshToken != null
}
