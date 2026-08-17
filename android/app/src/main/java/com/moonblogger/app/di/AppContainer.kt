package com.moonblogger.app.di

import android.content.Context
import com.moonblogger.app.BuildConfig
import com.moonblogger.app.data.auth.AuthRefresher
import com.moonblogger.app.data.auth.SessionManager
import com.moonblogger.app.data.media.MediaRepository
import com.moonblogger.app.data.media.PhotoSourceProvider
import com.moonblogger.app.data.media.SignedUrlUploader
import com.moonblogger.app.data.remote.AuthApi
import com.moonblogger.app.data.remote.PostsApi
import com.moonblogger.app.data.repository.AuthRepository
import com.moonblogger.app.data.repository.PostRepository
import com.moonblogger.app.data.token.EncryptedTokenStore
import com.moonblogger.app.data.token.TokenStore
import com.moonblogger.app.network.AuthInterceptor
import com.moonblogger.app.network.TokenAuthenticator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Contenedor de dependencias manual (decisión D7: sin Hilt en v1).
 *
 * Se construye por fases para evitar ciclos:
 * 1. TokenStore + client base (sin auth) → [AuthApi] (login/refresh públicos).
 * 2. [AuthRefresher] (single-flight, usa AuthApi) y [SessionManager].
 * 3. Client autenticado (AuthInterceptor + TokenAuthenticator) → [PostsApi].
 *
 * El [CoroutineScope] de aplicación se usa para el refresh single-flight:
 * debe sobrevivir a cada llamada individual (el Deferred en vuelo puede ser
 * creado por una petición y esperado por otra).
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // --- Fase 1: almacén de tokens y API pública de auth ---
    val tokenStore: TokenStore = EncryptedTokenStore(appContext)

    private val baseOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val authRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(baseOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val authApi: AuthApi = authRetrofit.create(AuthApi::class.java)

    // --- Fase 2: refresh single-flight y sesión ---
    val authRefresher: AuthRefresher = AuthRefresher(tokenStore, authApi, appScope)

    val sessionManager: SessionManager = SessionManager(tokenStore, authRefresher)

    // --- Fase 3: client autenticado y API de posts ---
    private val authedOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(tokenStore))
        .authenticator(TokenAuthenticator(authRefresher, sessionManager, tokenStore))
        .maybeAddLogging() // solo debug (ver NetworkLogging.kt)
        .build()

    // Este cliente se usa exclusivamente para PUT a URLs firmadas de Storage.
    // Deliberadamente no hereda AuthInterceptor, TokenAuthenticator ni logging.
    private val signedUploadOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val apiRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(authedOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val postsApi: PostsApi = apiRetrofit.create(PostsApi::class.java)

    // --- Repositorios ---
    val authRepository: AuthRepository = AuthRepository(authApi)
    val postRepository: PostRepository = PostRepository(postsApi)
    val mediaRepository: MediaRepository = MediaRepository(postsApi, SignedUrlUploader(signedUploadOkHttpClient))
    val photoSourceProvider: PhotoSourceProvider = PhotoSourceProvider(appContext.contentResolver, appContext.cacheDir)

    val viewModelFactory: MoonBloggerViewModelFactory = MoonBloggerViewModelFactory(this)
}
