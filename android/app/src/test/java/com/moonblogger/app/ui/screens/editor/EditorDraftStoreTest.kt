package com.moonblogger.app.ui.screens.editor

import com.moonblogger.app.data.model.CarouselTransition
import com.moonblogger.app.data.model.PostStatus
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class EditorDraftStoreTest {
    @Test fun `legacy draft without tag fields recovers compatible defaults`() {
        val legacy = """{"workingPostId":null,"originalStatus":"draft","title":"Old","content":"Text","status":"draft","transition":"slide","media":[],"deletedMediaIds":[],"isMediaDirty":false,"omittedLocalPhotos":false}"""
        val draft = kotlinx.serialization.json.Json.decodeFromString<EditorDraft>(legacy)
        assertEquals("Old", draft.title)
        assertTrue(draft.tags.isEmpty())
        assertFalse(draft.tagsDirty)
    }
    @Test fun `draft survives store recreation and stays isolated by account origin and post`() {
        val root = Files.createTempDirectory("editor-draft-test").toFile()
        try {
            val draft = EditorDraft(42, PostStatus.PUBLISHED, "Moon", "Escrito sin perder", PostStatus.DRAFT,
                CarouselTransition.SLIDE, listOf(DraftMedia("server:photo", "photo", "image", "image/jpeg", "Luna", "", true)),
                setOf("deleted-confirmation-pending"), true, true)
            FileEditorDraftStore(root, "moon", "https://api.test/", null).save(draft)
            assertEquals(draft, FileEditorDraftStore(root, "moon", "https://api.test/", null).load())
            val other = FileEditorDraftStore(root, "other", "https://api.test/", null)
            assertNull(other.load())
            other.clear()
            assertEquals(draft, FileEditorDraftStore(root, "moon", "https://api.test/", null).load())
            assertNull(FileEditorDraftStore(root, "moon", "https://other.test/", null).load())
            assertNull(FileEditorDraftStore(root, "moon", "https://api.test/", 42).load())
        } finally { root.deleteRecursively() }
    }
}
