package com.moonblogger.app.data.media

import com.moonblogger.app.data.model.CarouselTransition
import com.moonblogger.app.data.remote.ApiErrors
import com.moonblogger.app.data.remote.PostsApi
import com.moonblogger.app.testutil.SAMPLE_POST_JSON
import com.moonblogger.app.testutil.jsonResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class MediaRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: MediaRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
        repository = MediaRepository(
            retrofit.create(PostsApi::class.java),
            SignedUrlUploader(OkHttpClient()),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `photo upload requests intent PUTs exact bytes without auth then completes`() = runBlocking {
        val mediaId = "f7b0bd3c-25ee-45c4-8d4c-9f514f211a55"
        server.enqueue(
            jsonResponse(
                201,
                """{"media_id":"$mediaId","upload_url":"${server.url("/signed/asset")}","expires_at":"2026-08-16T12:00:00Z"}""",
            ),
        )
        server.enqueue(mockwebserver3.MockResponse(code = 200))
        server.enqueue(jsonResponse(200, """{"media_id":"$mediaId","state":"ready"}"""))

        val result = repository.uploadPhoto(
            5,
            UploadFile("image/png", 3, 20, 10) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )

        assertEquals(mediaId, result.getOrThrow())
        val intent = server.takeRequest()
        assertEquals("POST", intent.method)
        assertEquals("/api/v1/posts/5/media/upload-intents/", intent.url.encodedPath)
        val intentBody = intent.body!!.utf8()
        assertTrue(intentBody.contains("\"kind\":\"image\""))
        assertTrue(intentBody.contains("\"mime_type\":\"image/png\""))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/signed/asset", put.url.encodedPath)
        assertEquals("image/png", put.headers["Content-Type"])
        assertFalse(put.headers.names().any { it.equals("Authorization", ignoreCase = true) })
        assertEquals("\u0001\u0002\u0003", put.body!!.utf8())
        val complete = server.takeRequest()
        assertEquals("POST", complete.method)
        assertEquals("/api/v1/posts/5/media/complete/", complete.url.encodedPath)
        assertTrue(complete.body!!.utf8().contains(mediaId))
    }

    @Test
    fun `complete 400 retains media ID and exposes DRF detail`() = runBlocking {
        val mediaId = "f7b0bd3c-25ee-45c4-8d4c-9f514f211a55"
        server.enqueue(
            jsonResponse(
                201,
                """{"media_id":"$mediaId","upload_url":"${server.url("/signed/asset")}","expires_at":"2026-08-16T12:00:00Z"}""",
            ),
        )
        server.enqueue(mockwebserver3.MockResponse(code = 200))
        server.enqueue(jsonResponse(400, """{"detail":"El objeto cargado no coincide con el intent."}"""))

        val result = repository.uploadPhoto(
            5,
            UploadFile("image/png", 3, 20, 10) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )

        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is MediaUploadException)
        assertFalse(error is IOException)
        val uploadError = error as MediaUploadException
        assertEquals(mediaId, uploadError.mediaId)
        assertTrue(uploadError.cause is HttpException)
        assertEquals("El objeto cargado no coincide con el intent.", ApiErrors.userMessage(uploadError))
    }

    @Test
    fun `layout sends positions cover and selected transition`() = runBlocking {
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON))

        repository.updateLayout(
            postId = 8,
            media = listOf(LayoutMedia("first", true), LayoutMedia("second", false)),
            transition = CarouselTransition.BUBBLE,
        ).getOrThrow()

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/posts/8/media/layout/", request.url.encodedPath)
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"position\":0"))
        assertTrue(body.contains("\"is_cover\":true"))
        assertTrue(body.contains("\"carousel_transition\":\"bubble\""))
    }
}
