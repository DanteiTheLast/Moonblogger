package com.moonblogger.app.ui.screens.posts

import com.moonblogger.app.data.remote.PostsApi
import com.moonblogger.app.data.repository.PostRepository
import com.moonblogger.app.testutil.SAMPLE_POST_JSON
import com.moonblogger.app.testutil.jsonResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.*
import org.junit.Assert.*
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class PostsViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: PostsViewModel
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().also { it.start() }
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(PostsApi::class.java)
        vm = PostsViewModel(PostRepository(api))
    }
    @After fun teardown() { server.close(); Dispatchers.resetMain() }
    private suspend fun settled() = withTimeout(5000) { vm.uiState.first { !it.isLoading && !it.isLoadingMore && !it.isRefreshing } }
    @Test fun `expired or failed private cover requests require deliberate retry`() = runBlocking {
        val coverPost = kotlinx.serialization.json.Json.decodeFromString<com.moonblogger.app.data.model.Post>(SAMPLE_POST_JSON).copy(
            media = listOf(com.moonblogger.app.data.model.PostMedia("photo", "image", "ready", is_cover = true, mime_type = "image/jpeg", size_bytes = 100)))
        val encoded = kotlinx.serialization.json.Json.encodeToString(com.moonblogger.app.data.model.Post.serializer(), coverPost)
        server.enqueue(jsonResponse(200, """{"results":[$encoded]}"""))
        vm.onScreenResumed(); settled(); server.takeRequest()
        server.enqueue(jsonResponse(503, "{}"))
        vm.loadCover(coverPost)
        withTimeout(5000) { vm.uiState.first { 1L in it.coverErrors } }
        server.takeRequest()
        vm.loadCover(coverPost)
        assertEquals(2, server.requestCount)
        server.enqueue(jsonResponse(200, """{"expires_at":"2000-01-01T00:00:00Z","media":[{"id":"photo","url":"https://images.test/old"}]}"""))
        vm.loadCover(coverPost, retry = true)
        withTimeout(5000) { vm.uiState.first { it.coverUrls[1L] != null } }
        server.takeRequest()
        server.enqueue(jsonResponse(200, """{"expires_at":"2099-01-01T00:00:00Z","media":[{"id":"photo","url":"https://images.test/new"}]}"""))
        vm.loadCover(coverPost)
        withTimeout(5000) { vm.uiState.first { it.coverUrls[1L] == "https://images.test/new" } }
        assertEquals(4, server.requestCount)
    }

    @Test fun `next page failure preserves posts and retries same page without following next host`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"count":2,"next":"https://untrusted.test/?page=2","results":[$SAMPLE_POST_JSON]}"""))
        vm.onScreenResumed()
        assertTrue(settled().hasMore)
        server.takeRequest()
        server.enqueue(jsonResponse(503, "{}"))
        vm.loadMore()
        val failed = settled()
        assertEquals(1, failed.posts.size)
        assertTrue(failed.hasMore)
        assertNotNull(failed.error)
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
        server.enqueue(jsonResponse(200, """{"count":2,"next":null,"results":[${SAMPLE_POST_JSON.replace("\"id\": 1", "\"id\": 2")},$SAMPLE_POST_JSON]}"""))
        vm.loadMore()
        val completed = settled()
        assertEquals(listOf(1L, 2L), completed.posts.map { it.id })
        assertFalse(completed.hasMore)
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
    }
}
