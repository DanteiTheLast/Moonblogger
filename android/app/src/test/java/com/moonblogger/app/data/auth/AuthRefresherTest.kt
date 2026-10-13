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

        assertEquals(RefreshResult.SUCCESS, result)
        assertEquals("new-access", tokenStore.accessToken)
        assertEquals("new-refresh", tokenStore.refreshToken)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/auth/refresh/", recorded.url.encodedPath)
        assertTrue(recorded.body!!.utf8().contains("old-refresh"))
    }

    @Test
    fun `rejected refresh invalidates tokens`() = runBlocking {
        server.enqueue(jsonResponse(401, """{"detail":"Token is invalid or expired"}"""))
        tokenStore.saveTokens(access = "expired", refresh = "bad-refresh")

        val result = refresher.refreshAccessToken()

        assertEquals(RefreshResult.INVALID, result)
        assertEquals(null, tokenStore.accessToken)
        assertEquals(null, tokenStore.refreshToken)
    }

    @Test
    fun `refresh without stored token does not call network`() = runBlocking {
        val result = refresher.refreshAccessToken()

        assertEquals(RefreshResult.SESSION_CHANGED, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `concurrent refreshes are single flight`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"access":"new-access","refresh":"new-refresh"}"""))
        tokenStore.saveTokens(access = "old-access", refresh = "old-refresh")

        val results = (1..8).map { async { refresher.refreshAccessToken() } }.awaitAll()

        assertTrue(results.all { it == RefreshResult.SUCCESS })
        assertEquals(1, server.requestCount)
        assertEquals("new-access", tokenStore.accessToken)
    }

    @Test
    fun `failed refresh is not cached`() = runBlocking {
        server.enqueue(jsonResponse(401, """{"detail":"invalid"}"""))
        tokenStore.saveTokens(access = "expired", refresh = "bad-refresh")
        assertEquals(RefreshResult.INVALID, refresher.refreshAccessToken())

        tokenStore.saveTokens("expired", "bad-refresh")
        // Un segundo intento vuelve a llamar a la red (no se cachea el fallo).
        server.enqueue(jsonResponse(200, """{"access":"ok-access","refresh":"ok-refresh"}"""))
        assertEquals(RefreshResult.SUCCESS, refresher.refreshAccessToken())
        assertEquals(2, server.requestCount)
    }
    @Test
    fun `server outage preserves tokens and initialization stays logged in`() = runBlocking {
        server.enqueue(jsonResponse(503, "{}"))
        val expired = com.moonblogger.app.testutil.TestJwt.withExp(1)
        tokenStore.saveTokens(expired, "refresh")
        val session = SessionManager(tokenStore, refresher)
        session.initialize()
        assertEquals(SessionState.LoggedIn, session.state.value)
        assertEquals("refresh", tokenStore.refreshToken)
    }

    @Test
    fun `refresh response cannot repopulate tokens after logout`() = runBlocking {
        tokenStore.saveTokens("old", "refresh")
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                received.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return jsonResponse(200, """{"access":"new","refresh":"rotated"}""")
            }
        }
        val session = SessionManager(tokenStore, refresher)
        val pending = async(kotlinx.coroutines.Dispatchers.IO) { refresher.refreshAccessToken() }
        assertTrue(received.await(5, java.util.concurrent.TimeUnit.SECONDS))
        session.logout()
        release.countDown()
        assertEquals(RefreshResult.SESSION_CHANGED, pending.await())
        assertFalse(tokenStore.hasTokens())
        assertEquals(SessionState.LoggedOut, session.state.value)
    }

    @Test
    fun `cancelling one waiter cannot launch a second rotating refresh`() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val api = object : AuthApi {
            override suspend fun login(body: com.moonblogger.app.data.model.LoginRequest): com.moonblogger.app.data.model.TokenResponse = error("No login")
            override suspend fun refresh(body: com.moonblogger.app.data.model.RefreshRequest): com.moonblogger.app.data.model.TokenResponse {
                calls++
                started.complete(Unit)
                release.await()
                return com.moonblogger.app.data.model.TokenResponse("new", "rotated")
            }
        }
        val applicationJob = SupervisorJob()
        val shared = AuthRefresher(tokenStore, api, CoroutineScope(applicationJob + Dispatchers.Unconfined))
        tokenStore.saveTokens("old", "refresh")
        val first = async { shared.refreshAccessToken() }
        started.await()
        first.cancel()
        first.join()
        val second = async { shared.refreshAccessToken() }
        kotlinx.coroutines.yield()
        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(RefreshResult.SUCCESS, second.await())
        applicationJob.cancel()
    }

}
