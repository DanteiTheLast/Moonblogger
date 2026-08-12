package com.moonblogger.app.network

import com.moonblogger.app.data.auth.AuthRefresher
import com.moonblogger.app.data.auth.SessionManager
import com.moonblogger.app.data.auth.SessionState
import com.moonblogger.app.data.remote.AuthApi
import com.moonblogger.app.testutil.FakeTokenStore
import com.moonblogger.app.testutil.jsonResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Pruebas del flujo completo AuthInterceptor + TokenAuthenticator contra un
 * MockWebServer: 401 por access caducado → refresh (single-flight) → reintento.
 *
 * El AuthApi del refresher usa un client SIN authenticator (como en
 * producción: AppContainer separa el client público del autenticado).
 */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var sessionManager: SessionManager
    private lateinit var client: OkHttpClient

    private var refreshHits = 0
    private val recordedRequests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeTokenStore()

        val json = Json { ignoreUnknownKeys = true }
        val authApi: AuthApi = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)

        val refresher = AuthRefresher(
            tokenStore = tokenStore,
            authApi = authApi,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        sessionManager = SessionManager(tokenStore, refresher)

        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(refresher, sessionManager, tokenStore))
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun installDispatcher(
        refreshShouldFail: Boolean = false,
        postsResponse: (RecordedRequest) -> MockResponse,
    ) {
        refreshHits = 0
        recordedRequests.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests += request
                return when (request.url.encodedPath) {
                    "/api/v1/auth/refresh/" -> {
                        refreshHits++
                        if (refreshShouldFail) {
                            jsonResponse(401, """{"detail":"Token is invalid or expired"}""")
                        } else {
                            jsonResponse(
                                200,
                                """{"access":"new-access","refresh":"new-refresh"}""",
                            )
                        }
                    }

                    "/api/v1/auth/login/" ->
                        jsonResponse(401, """{"detail":"No active account found"}""")

                    "/api/v1/posts/" -> postsResponse(request)

                    else -> MockResponse(code = 404)
                }
            }
        }
    }

    private fun get(path: String): okhttp3.Response =
        client.newCall(
            Request.Builder()
                .url(server.url(path))
                .build(),
        ).execute()

    @Test
    fun `expired access is refreshed and request is retried once`() {
        tokenStore.saveTokens(access = "expired-access", refresh = "old-refresh")
        installDispatcher { request ->
            if (request.headers["Authorization"] == "Bearer new-access") {
                jsonResponse(200, """{"count":0,"next":null,"previous":null,"results":[]}""")
            } else {
                jsonResponse(401, """{"detail":"Signature has expired"}""")
            }
        }

        val response = get("api/v1/posts/")

        assertEquals(200, response.code)
        assertEquals(1, refreshHits)
        assertEquals("new-access", tokenStore.accessToken)
        assertEquals("new-refresh", tokenStore.refreshToken)

        // El reintento lleva el header anti-bucle.
        val retry = recordedRequests.last { it.url.encodedPath == "/api/v1/posts/" }
        assertEquals("1", retry.headers["X-MoonBlogger-Auth-Retry"])
    }

    @Test
    fun `refresh failure logs out and does not retry`() {
        tokenStore.saveTokens(access = "expired-access", refresh = "bad-refresh")
        installDispatcher(refreshShouldFail = true) {
            jsonResponse(401, """{"detail":"Signature has expired"}""")
        }

        val response = get("api/v1/posts/")

        assertEquals(401, response.code)
        assertEquals(1, refreshHits)
        assertFalse(tokenStore.hasTokens())
        assertEquals(SessionState.LoggedOut, sessionManager.state.value)
    }

    @Test
    fun `login 401 does not refresh nor logout`() {
        tokenStore.saveTokens(access = "some-access", refresh = "some-refresh")
        installDispatcher { error("no debe llamarse") }

        val response = client.newCall(
            Request.Builder()
                .url(server.url("api/v1/auth/login/"))
                .post("""{"username":"moon","password":"wrong"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()

        assertEquals(401, response.code)
        assertEquals(0, refreshHits)
        assertTrue(tokenStore.hasTokens())
    }

    @Test
    fun `retried request still 401 is not retried again`() {
        tokenStore.saveTokens(access = "expired-access", refresh = "old-refresh")
        installDispatcher { jsonResponse(401, """{"detail":"Still unauthorized"}""") }

        val response = get("api/v1/posts/")

        assertEquals(401, response.code)
        assertEquals(1, refreshHits) // un solo refresh, sin bucle infinito
        assertEquals(2, recordedRequests.count { it.url.encodedPath == "/api/v1/posts/" })
    }
}
