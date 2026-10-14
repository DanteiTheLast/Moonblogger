package com.moonblogger.app.ui.screens.editor

import com.moonblogger.app.data.model.CarouselTransition
import com.moonblogger.app.data.model.PostStatus
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** No tokens, read URLs or temporary gallery permissions are serialized. */
@Serializable
data class EditorDraft(
    val workingPostId: Long?,
    val originalStatus: PostStatus,
    val title: String,
    val content: String,
    val status: PostStatus,
    val transition: CarouselTransition,
    val media: List<DraftMedia>,
    val deletedMediaIds: Set<String>,
    val isMediaDirty: Boolean,
    val omittedLocalPhotos: Boolean,
    val schemaVersion: Int = 2,
    val tags: List<com.moonblogger.app.data.model.Tag> = emptyList(),
    val tagsDirty: Boolean = false,
)

@Serializable
data class DraftMedia(
    val key: String,
    val mediaId: String,
    val kind: String,
    val mimeType: String,
    val altText: String,
    val caption: String,
    val isCover: Boolean,
)

interface EditorDraftStore {
    fun load(): EditorDraft?
    fun save(draft: EditorDraft)
    fun clear()
}

/** Scoped to account, API origin and original navigation ID. Private, excluded from backup. */
class FileEditorDraftStore(root: File, account: String, apiOrigin: String, postId: Long?) : EditorDraftStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = MessageDigest.getInstance("SHA-256")
        .digest("$apiOrigin\u0000$account".toByteArray()).joinToString("") { "%02x".format(it) }
    private val directory = File(root, "editor-drafts/$scope")
    private val file = File(directory, "${postId ?: "new"}.json")

    @Synchronized
    override fun load(): EditorDraft? = if (!file.exists()) null else json.decodeFromString(file.readText())

    @Synchronized
    override fun save(draft: EditorDraft) {
        check(directory.exists() || directory.mkdirs()) { "No se pudo crear el almacenamiento privado." }
        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(draft).toByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun clear() {
        check(!file.exists() || file.delete()) { "No se pudo descartar la copia local." }
        val temporary = File(directory, "${file.name}.tmp")
        check(!temporary.exists() || temporary.delete()) { "No se pudo descartar la copia local." }
    }
}
