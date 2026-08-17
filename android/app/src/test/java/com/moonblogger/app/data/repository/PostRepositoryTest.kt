package com.moonblogger.app.data.repository

import com.moonblogger.app.data.model.PostStatus
import com.moonblogger.app.data.model.CarouselTransition
import com.moonblogger.app.data.remote.PostsApi
import com.moonblogger.app.testutil.SAMPLE_POST_JSON
import com.moonblogger.app.testutil.jsonResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class PostRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: PostRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        repository = PostRepository(retrofit.create(PostsApi::class.java))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `listPosts parses paginated results`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"count":1,"next":null,"previous":null,"results":[$SAMPLE_POST_JSON]}""",
            ),
        )

        val posts = repository.listPosts().getOrThrow()

        assertEquals(1, posts.size)
        assertEquals("Mi primer post", posts[0].title)
        assertEquals(PostStatus.DRAFT, posts[0].status)
    }

    @Test
    fun `getPost hits the right path and parses`() = runBlocking {
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON))

        val post = repository.getPost(1L).getOrThrow()

        assertEquals(1L, post.id)
        assertEquals("draft", post.status.name.lowercase())
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/posts/1/", recorded.url.encodedPath)
    }

    @Test
    fun `getPost parses private media and carousel transition`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{
                    "id":1,"slug":"con-fotos","title":"Con fotos","content":"Hola",
                    "status":"draft","created_at":"2026-08-11T12:00:00Z",
                    "updated_at":"2026-08-11T12:30:00Z","published_at":null,
                    "carousel_transition":"fade",
                    "media":[{"id":"a5d5488d-60f9-4b0c-a2ae-7e9b9f8e59a9","kind":"image",
                    "state":"ready","position":0,"is_cover":true,"mime_type":"image/jpeg",
                    "size_bytes":12,"width":3,"height":4,"alt_text":"Luna","caption":"Noche"}]
                }""",
            ),
        )

        val post = repository.getPost(1L).getOrThrow()

        assertEquals(CarouselTransition.FADE, post.carousel_transition)
        assertEquals(1, post.media.size)
        assertEquals("Luna", post.media.single().alt_text)
        assertTrue(post.media.single().is_cover)
    }

    @Test
    fun `createPost serializes the request body`() = runBlocking {
        server.enqueue(jsonResponse(201, SAMPLE_POST_JSON))

        val post = repository.createPost("Mi primer post", "Hola mundo", PostStatus.DRAFT).getOrThrow()

        assertEquals("Mi primer post", post.title)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/posts/", recorded.url.encodedPath)
        val body = recorded.body!!.utf8()
        assertTrue(body.contains("\"title\":\"Mi primer post\""))
        assertTrue(body.contains("\"status\":\"draft\""))
    }

    @Test
    fun `updatePost uses PATCH`() = runBlocking {
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON))

        repository.updatePost(7L, "Título", "Contenido", PostStatus.PUBLISHED).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/posts/7/", recorded.url.encodedPath)
        assertTrue(recorded.body!!.utf8().contains("\"status\":\"published\""))
    }

    @Test
    fun `deletePost sends DELETE and 204 is success`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        val result = repository.deletePost(3L)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/posts/3/", recorded.url.encodedPath)
    }

    @Test
    fun `400 field errors become a failure`() = runBlocking {
        server.enqueue(jsonResponse(400, """{"title":["El título no puede estar vacío."]}"""))

        val result = repository.createPost("", "contenido", PostStatus.DRAFT)

        assertTrue(result.isFailure)
    }
}
