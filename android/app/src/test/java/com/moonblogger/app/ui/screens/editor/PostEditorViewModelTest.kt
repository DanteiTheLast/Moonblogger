package com.moonblogger.app.ui.screens.editor

import android.net.Uri
import com.moonblogger.app.data.media.*
import com.moonblogger.app.data.model.*
import com.moonblogger.app.data.remote.PostsApi
import com.moonblogger.app.data.repository.PostRepository
import com.moonblogger.app.testutil.SAMPLE_POST_JSON
import com.moonblogger.app.testutil.jsonResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class PostEditorViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: PostRepository
    private lateinit var mediaRepository: MediaRepository
    private val photos = object : PhotoSource {
        override fun inspect(uris: List<Uri>) = PhotoSelectionResult(emptyList(), emptyList())
        override fun uploadFile(photo: InspectedPhoto): UploadFile = error("No local upload expected")
        override fun release(photo: InspectedPhoto) = Unit
    }
    private class MemoryDraft : EditorDraftStore {
        var value: EditorDraft? = null
        override fun load() = value
        override fun save(draft: EditorDraft) { value = draft }
        override fun clear() { value = null }
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().also { it.start() }
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(PostsApi::class.java)
        repository = PostRepository(api)
        mediaRepository = MediaRepository(api, SignedUrlUploader(OkHttpClient()))
    }
    @After fun teardown() { server.close(); Dispatchers.resetMain() }
    private suspend fun settled(vm: PostEditorViewModel) = withTimeout(5000) {
        vm.uiState.first { !it.isLoading && !it.isSaving }
    }
    private fun vm(id: Long? = null, store: EditorDraftStore? = null) =
        PostEditorViewModel(repository, mediaRepository, photos, id, store)

    @Test fun `selected tags survive draft recovery and cap at five`() = runBlocking {
        server.enqueue(jsonResponse(200, """[{"slug":"diario","name":"Diario"},{"slug":"ideas","name":"Ideas"},{"slug":"relatos","name":"Relatos"},{"slug":"viajes","name":"Viajes"},{"slug":"dan","name":"Dan"},{"slug":"mascotas","name":"Mascotas"}]"""))
        val store = MemoryDraft()
        val editor = vm(store = store)
        editor.loadTags()
        withTimeout(5000) { editor.uiState.first { !it.tagsLoading && it.tagCatalog.isNotEmpty() } }
        listOf("diario", "ideas", "relatos", "viajes", "dan", "mascotas").forEach(editor::toggleTag)
        assertEquals(5, editor.uiState.value.tags.size)
        val recovered = vm(store = store)
        assertEquals(editor.uiState.value.tags, recovered.uiState.value.tags)
        assertTrue(recovered.uiState.value.tagsDirty)
    }

    @Test fun `catalog failure preserves existing tags and unchanged save omits field`() = runBlocking {
        val store = MemoryDraft().apply { value = EditorDraft(1, PostStatus.DRAFT, "Text", "Content", PostStatus.DRAFT,
            CarouselTransition.SLIDE, emptyList(), emptySet(), false, false, tags = listOf(Tag("dan", "Dan"))) }
        server.enqueue(jsonResponse(500, "{}"))
        val editor = vm(store = store)
        editor.loadTags()
        withTimeout(5000) { editor.uiState.first { it.tagsError != null } }
        assertEquals(listOf(Tag("dan", "Dan")), editor.uiState.value.tags)
        assertFalse(editor.uiState.value.tagsDirty)
        server.takeRequest()
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON))
        editor.onContentChange("Edited")
        editor.save(); settled(editor)
        assertFalse(server.takeRequest().body!!.utf8().contains("tag_slugs"))
    }

    @Test fun `initial load failure cannot overwrite original and retry loads it`() = runBlocking {
        server.enqueue(jsonResponse(500, "{}"))
        val editor = vm(1)
        editor.start()
        val failed = settled(editor)
        assertNotNull(failed.loadError)
        editor.onTitleChange("Overwrite")
        editor.onContentChange("No")
        editor.save()
        assertEquals("", editor.uiState.value.title)
        assertFalse(editor.uiState.value.canSubmit)
        assertEquals(1, server.requestCount)
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON))
        editor.retryLoad()
        assertEquals("Mi primer post", settled(editor).title)
        assertNull(editor.uiState.value.loadError)
    }

    @Test fun `local text survives editor recreation without requesting blank server data`() {
        val store = MemoryDraft()
        val first = vm(store = store)
        first.onTitleChange("Escrito")
        first.onContentChange("Sobre la luna")
        first.onStatusChange(PostStatus.PUBLISHED)
        val restored = vm(store = store)
        restored.start()
        assertEquals("Sobre la luna", restored.uiState.value.content)
        assertEquals(PostStatus.PUBLISHED, restored.uiState.value.status)
        assertTrue(restored.uiState.value.hasChanges)
        assertEquals(0, server.requestCount)
    }

    @Test fun `restored working id updates instead of duplicating a partially saved new post`() = runBlocking {
        val store = MemoryDraft().apply {
            value = EditorDraft(1, PostStatus.DRAFT, "Recovered", "Text", PostStatus.DRAFT,
                CarouselTransition.SLIDE, emptyList(), emptySet(), false, false)
        }
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON))
        val editor = vm(store = store)
        editor.save()
        assertTrue(settled(editor).isSaved)
        assertEquals("PATCH", server.takeRequest().method)
        assertNull(store.value)
    }

    @Test fun `missing local photos require review before publishing recovered text`() {
        val store = MemoryDraft().apply {
            value = EditorDraft(1, PostStatus.DRAFT, "Recovered", "Text", PostStatus.PUBLISHED,
                CarouselTransition.SLIDE, emptyList(), emptySet(), true, true)
        }
        val editor = vm(store = store)
        assertFalse(editor.uiState.value.canSubmit)
        editor.save()
        assertEquals(0, server.requestCount)
        editor.acknowledgeMissingPhotos()
        assertTrue(editor.uiState.value.canSubmit)
    }

    @Test fun `confirmed delete is removed from retry and 404 is idempotent`() = runBlocking {
        val store = MemoryDraft().apply {
            value = EditorDraft(1, PostStatus.PUBLISHED, "Recovered", "Text", PostStatus.PUBLISHED,
                CarouselTransition.SLIDE, emptyList(), setOf("removed"), true, false)
        }
        val published = SAMPLE_POST_JSON.replace("\"draft\"", "\"published\"")
        server.enqueue(jsonResponse(200, published)) // preparation retains original status
        server.enqueue(jsonResponse(404, "{}")) // delete response was previously lost
        server.enqueue(jsonResponse(500, "{}")) // layout fails after delete
        val first = vm(1, store)
        first.save()
        assertNotNull(settled(first).error)
        assertTrue(store.value!!.deletedMediaIds.isEmpty())
        val prepare = server.takeRequest()
        assertFalse(prepare.body!!.utf8().contains("status"))
        assertEquals("DELETE", server.takeRequest().method)
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/layout/"))
        server.enqueue(jsonResponse(200, published))
        server.enqueue(jsonResponse(200, published))
        server.enqueue(jsonResponse(200, published))
        val retry = vm(1, store)
        retry.save()
        assertTrue(settled(retry).isSaved)
        assertEquals("PATCH", server.takeRequest().method)
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/layout/"))
        assertEquals("PATCH", server.takeRequest().method)
    }
    @Test fun `saving blocks further edits so confirmed snapshot cannot delete new writing`() = runBlocking {
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                received.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return jsonResponse(200, SAMPLE_POST_JSON)
            }
        }
        val store = MemoryDraft()
        val editor = vm(store = store)
        editor.onTitleChange("Before")
        editor.onContentChange("Saved snapshot")
        editor.save()
        assertTrue(received.await(5, java.util.concurrent.TimeUnit.SECONDS))
        editor.onContentChange("Unsaved replacement")
        editor.save()
        assertEquals("Saved snapshot", editor.uiState.value.content)
        assertEquals(1, server.requestCount)
        release.countDown()
        assertTrue(settled(editor).isSaved)
    }

    @Test fun `unreadable new draft requires explicit discard and then allows writing`() {
        var cleared = false
        val store = object : EditorDraftStore {
            override fun load(): EditorDraft? = throw java.io.IOException("corrupt")
            override fun save(draft: EditorDraft) { check(cleared) }
            override fun clear() { cleared = true }
        }
        val editor = vm(store = store)
        editor.start()
        editor.retryLoad()
        editor.onTitleChange("Must remain blocked")
        assertTrue(editor.uiState.value.localRecoveryError)
        assertFalse(cleared)
        assertEquals("", editor.uiState.value.title)
        editor.discardFailedRecovery()
        assertTrue(cleared)
        assertFalse(editor.uiState.value.localRecoveryError)
        assertNull(editor.uiState.value.loadError)
        editor.onTitleChange("Recovered editor")
        editor.onContentChange("New writing")
        assertTrue(editor.uiState.value.canSubmit)
        assertEquals(0, server.requestCount)
    }

    @Test fun `remote editor excludes ready uploads that are absent from the active layout`() = runBlocking {
        val media = """[{"id":"active","kind":"image","state":"ready","position":0,"mime_type":"image/jpeg","size_bytes":1,"is_cover":true},
            {"id":"orphan","kind":"image","state":"ready","position":null,"mime_type":"image/jpeg","size_bytes":1}]"""
        val post = SAMPLE_POST_JSON.trim().dropLast(1) + ",\"media\":" + media + "}"
        server.enqueue(jsonResponse(200, post))
        val editor = vm(1)
        editor.start()
        assertEquals(listOf("active"), settled(editor).media.map { it.mediaId })
    }

    @Test fun `local checkpoint keeps completed upload absent from remote active layout`() = runBlocking {
        val store = MemoryDraft().apply {
            value = EditorDraft(1, PostStatus.DRAFT, "Recovered", "Text", PostStatus.DRAFT,
                CarouselTransition.SLIDE,
                listOf(DraftMedia("local:completed", "completed-upload", "image", "image/jpeg", "Luna", "", true)),
                emptySet(), true, false)
        }
        val editor = vm(1, store)
        editor.start()
        assertEquals(listOf("completed-upload"), editor.uiState.value.media.map { it.mediaId })
        assertEquals(0, server.requestCount)
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON))
        server.enqueue(mockwebserver3.MockResponse(code = 204)) // metadata
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON)) // layout activates retained ID
        server.enqueue(jsonResponse(200, SAMPLE_POST_JSON)) // final publication state
        editor.save()
        assertTrue(settled(editor).isSaved)
        server.takeRequest()
        assertTrue(server.takeRequest().url.encodedPath.contains("completed-upload"))
        assertTrue(server.takeRequest().body!!.utf8().contains("completed-upload"))
        server.takeRequest()
        Unit
    }

}
