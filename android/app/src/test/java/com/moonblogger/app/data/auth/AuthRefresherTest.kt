package com.moonblogger.app.data.auth

import com.moonblogger.app.data.remote.AuthApi
import com.moonblogger.app.testutil.FakeTokenStore
import com.moonblogger.app.testutil.jsonResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AuthRefresherTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var refresher: AuthRefresher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeTokenStore()

        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val authApi: AuthApi = retrofit.create(AuthApi::class.java)

        refresher = AuthRefresher(
            tokenStore = tokenStore,
            authApi = authApi,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `refresh succeeds and persists rotated tokens`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"access":"new-access","refresh":"new-refresh"}"""))
        tokenStore.saveTokens(access = "old-access", refresh = "old-refresh")

        val result = refresher.refreshAccessToken()

        assertTrue(result)
        assertEquals("new-access", tokenStore.accessToken)
        assertEquals("new-refresh", tokenStore.refreshToken)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/auth/refresh/", recorded.url.encodedPath)
        assertTrue(recorded.body!!.utf8().contains("old-refresh"))
    }

    @Test
    fun `refresh failure returns false and keeps tokens`() = runBlocking {
        server.enqueue(jsonResponse(401, """{"detail":"Token is invalid or expired"}"""))
        tokenStore.saveTokens(access = "expired", refresh = "bad-refresh")

        val result = refresher.refreshAccessToken()

        assertFalse(result)
        assertEquals("expired", tokenStore.accessToken)
        assertEquals("bad-refresh", tokenStore.refreshToken)
    }

    @Test
    fun `refresh without stored token returns false without network`() = runBlocking {
        val result = refresher.refreshAccessToken()

        assertFalse(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `concurrent refreshes are single flight`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"access":"new-access","refresh":"new-refresh"}"""))
        tokenStore.saveTokens(access = "old-access", refresh = "old-refresh")

        val results = (1..8).map { async { refresher.refreshAccessToken() } }.awaitAll()

        assertTrue(results.all { it })
        assertEquals(1, server.requestCount)
        assertEquals("new-access", tokenStore.accessToken)
    }

    @Test
    fun `failed refresh is not cached`() = runBlocking {
        server.enqueue(jsonResponse(401, """{"detail":"invalid"}"""))
        tokenStore.saveTokens(access = "expired", refresh = "bad-refresh")
        assertFalse(refresher.refreshAccessToken())

        // Un segundo intento vuelve a llamar a la red (no se cachea el fallo).
        server.enqueue(jsonResponse(200, """{"access":"ok-access","refresh":"ok-refresh"}"""))
        assertTrue(refresher.refreshAccessToken())
        assertEquals(2, server.requestCount)
    }
}
