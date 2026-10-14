package com.moonblogger.app.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonblogger.app.data.media.InspectedPhoto
import com.moonblogger.app.data.media.LayoutMedia
import com.moonblogger.app.data.media.MediaRepository
import com.moonblogger.app.data.media.MediaUploadException
import com.moonblogger.app.data.media.PhotoSource
import com.moonblogger.app.data.media.PhotoRejectionReason
import com.moonblogger.app.data.model.CarouselTransition
import com.moonblogger.app.data.model.PostMedia
import com.moonblogger.app.data.model.PostStatus
import com.moonblogger.app.data.remote.ApiErrors
import com.moonblogger.app.data.repository.PostRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorMedia(
    val key: String,
    val mediaId: String? = null,
    val localPhoto: InspectedPhoto? = null,
    val kind: String = "image",
    val mimeType: String = "",
    val altText: String = "",
    val caption: String = "",
    val isCover: Boolean = false,
) {
    val isLocal: Boolean get() = localPhoto != null
}

data class PostEditorUiState(
    val isEdit: Boolean = false,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val localRecoveryError: Boolean = false,
    val localNotice: String? = null,
    val localCopyAvailable: Boolean = false,
    val hasChanges: Boolean = false,
    val needsPhotoReview: Boolean = false,
    val tags: List<com.moonblogger.app.data.model.Tag> = emptyList(),
    val tagCatalog: List<com.moonblogger.app.data.model.Tag> = emptyList(),
    val tagsDirty: Boolean = false,
    val tagsLoading: Boolean = false,
    val tagsError: String? = null,
    val title: String = "",
    val content: String = "",
    val status: PostStatus = PostStatus.DRAFT,
    val transition: CarouselTransition = CarouselTransition.SLIDE,
    val media: List<EditorMedia> = emptyList(),
    val deletedMediaIds: Set<String> = emptySet(),
    val isMediaDirty: Boolean = false,
    /** Hay URIs concedidas temporalmente copiándose e inspeccionándose en IO. */
    val isInspecting: Boolean = false,
    val isSaving: Boolean = false,
    /** Número de fotos confirmadas o en curso frente al total local a subir. */
    val uploadProgress: Pair<Int, Int>? = null,
    val uploadMessage: String? = null,
    val error: String? = null,
    val isSaved: Boolean = false,
) {
    val canSubmit: Boolean
        get() = title.isNotBlank() && content.isNotBlank() && !isSaving && !isInspecting && !isLoading && loadError == null && !needsPhotoReview
}

/** Editor de posts y fotos: las cargas se completan antes de publicar. */
class PostEditorViewModel(
    private val repository: PostRepository,
    private val mediaRepository: MediaRepository,
    private val photoSourceProvider: PhotoSource,
    private val postId: Long?,
    private val draftStore: EditorDraftStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostEditorUiState(isEdit = postId != null, isLoading = postId != null))
    val uiState: StateFlow<PostEditorUiState> = _uiState.asStateFlow()
    private var initialLoadStarted = false
    private var workingPostId: Long? = postId
    private var originalStatus: PostStatus = PostStatus.DRAFT

    init {
        try {
            draftStore?.load()?.let { draft ->
                workingPostId = draft.workingPostId
                originalStatus = draft.originalStatus
                initialLoadStarted = true
                _uiState.value = PostEditorUiState(
                    isEdit = postId != null, title = draft.title, content = draft.content,
                    status = draft.status, transition = draft.transition, tags = draft.tags, tagsDirty = draft.tagsDirty,
                    media = draft.media.map { EditorMedia(it.key, it.mediaId, kind = it.kind,
                        mimeType = it.mimeType, altText = it.altText, caption = it.caption, isCover = it.isCover) }.ensureCover(),
                    deletedMediaIds = draft.deletedMediaIds, isMediaDirty = draft.isMediaDirty,
                    localCopyAvailable = true, hasChanges = true, needsPhotoReview = draft.omittedLocalPhotos,
                    localNotice = if (draft.omittedLocalPhotos) "Se recuperó el escrito. Las fotos que no terminaron de subir no se conservan: vuelve a elegirlas antes de guardar."
                        else "Se recuperó una copia local del escrito y los pasos de guardado confirmados.",
                )
            }
        } catch (_: Exception) {
            _uiState.update { it.copy(isLoading = false, localRecoveryError = true, loadError = "No se pudo leer la copia local. Vuelve a abrir el editor para intentarlo otra vez, o descártala explícitamente para continuar.") }
            initialLoadStarted = true
        }
    }

    fun acknowledgeMissingPhotos() {
        _uiState.update { it.copy(needsPhotoReview = false) }
        persist()
    }

    private fun persist() {
        val state = _uiState.value
        if (!state.hasChanges || state.loadError != null || state.isSaved) return
        val store = draftStore
        if (store == null) {
            _uiState.update { it.copy(localCopyAvailable = false, localNotice = "No se pudo identificar la cuenta para guardar una copia local. Conserva el escrito antes de salir.") }
            return
        }
        try {
            store.save(EditorDraft(workingPostId, originalStatus, state.title, state.content, state.status,
                state.transition, state.media.mapNotNull { item -> item.mediaId?.let {
                    DraftMedia(item.key, it, item.kind, item.mimeType, item.altText, item.caption, item.isCover)
                } }, state.deletedMediaIds, state.isMediaDirty, state.needsPhotoReview || state.media.any { it.mediaId == null && it.isLocal }, tags = state.tags, tagsDirty = state.tagsDirty))
            _uiState.update { it.copy(localCopyAvailable = true,
                localNotice = if (it.localNotice?.startsWith("No se pudo guardar") == true) null else it.localNotice) }
        } catch (_: Exception) {
            _uiState.update { it.copy(localCopyAvailable = false, localNotice = "No se pudo guardar la copia local. Conserva el escrito antes de salir.") }
        }
    }

    private fun change(transform: (PostEditorUiState) -> PostEditorUiState) {
        if (_uiState.value.isSaving || _uiState.value.isLoading || _uiState.value.loadError != null) return
        _uiState.update { transform(it).copy(hasChanges = true) }
        persist()
    }

    fun discardLocal(): Boolean = try {
        draftStore?.clear()
        _uiState.value.media.mapNotNull { it.localPhoto }.forEach(photoSourceProvider::release)
        true
    } catch (_: Exception) {
        _uiState.update { it.copy(error = "No se pudo descartar la copia local. Inténtalo otra vez.") }
        false
    }

    fun discardFailedRecovery() {
        if (!_uiState.value.localRecoveryError || !discardLocal()) return
        workingPostId = postId
        initialLoadStarted = false
        _uiState.value = PostEditorUiState(isEdit = postId != null, isLoading = postId != null)
        start()
    }

    fun retryLoad() {
        if (_uiState.value.loadError == null || _uiState.value.hasChanges || _uiState.value.localRecoveryError) return
        initialLoadStarted = false
        start()
    }

    fun start() {
        val id = postId ?: return
        if (initialLoadStarted) return
        initialLoadStarted = true
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, loadError = null) }
            repository.getPost(id)
                .onSuccess { post ->
                    originalStatus = post.status
                    val media = post.media
                        .filter { it.state == READY_STATE && it.position != null }
                        .sortedWith(compareBy<PostMedia> { it.position ?: Int.MAX_VALUE }.thenBy { it.id })
                        .map { it.toEditorMedia() }
                        .ensureCover()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            title = post.title.take(MAX_TITLE_LENGTH),
                            content = post.content,
                            tags = post.tags, tagsDirty = false,
                            status = post.status,
                            transition = post.carousel_transition,
                            media = media,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, loadError = ApiErrors.userMessage(error)) }
                }
        }
    }

    fun loadTags() {
        if (_uiState.value.tagsLoading || _uiState.value.tagCatalog.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(tagsLoading = true, tagsError = null) }
            repository.listTags().onSuccess { catalog ->
                _uiState.update { it.copy(tagCatalog = catalog, tagsLoading = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(tagsLoading = false, tagsError = ApiErrors.userMessage(error)) }
            }
        }
    }

    fun toggleTag(slug: String) {
        val state = _uiState.value
        if (state.tagsLoading || state.tagsError != null) return
        val selected = state.tags.any { it.slug == slug }
        val tag = (state.tagCatalog + state.tags).firstOrNull { it.slug == slug } ?: return
        if (!selected && state.tags.size >= 5) return
        change { it.copy(tags = if (selected) it.tags.filterNot { item -> item.slug == slug } else it.tags + tag,
            tagsDirty = true, error = null) }
    }

    fun onTitleChange(value: String) = change { it.copy(title = value.take(MAX_TITLE_LENGTH), error = null) }

    fun onContentChange(value: String) = change { it.copy(content = value, error = null) }

    fun onStatusChange(value: PostStatus) = change { it.copy(status = value, error = null) }

    fun onTransitionChange(value: CarouselTransition) =
        change { it.copy(transition = value, isMediaDirty = true, error = null) }

    /** Se invoca con URIs otorgados temporalmente por Photo Picker, nunca con permiso de galería. */
    fun onPhotosSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        var inspectionStarted = false
        _uiState.update { state ->
            if (state.isSaving || state.isInspecting || state.isLoading || state.loadError != null) {
                state
            } else {
                inspectionStarted = true
                state.copy(isInspecting = true, error = null)
            }
        }
        if (!inspectionStarted) return
        viewModelScope.launch {
            val inspected = withContext(Dispatchers.IO) { photoSourceProvider.inspect(uris) }
            val state = _uiState.value
            if (state.isSaving) {
                inspected.photos.forEach(photoSourceProvider::release)
                _uiState.update { it.copy(isInspecting = false) }
                return@launch
            }
            val slots = (MAX_PHOTOS - state.media.size).coerceAtLeast(0)
            val acceptedPhotos = inspected.photos.take(slots)
            inspected.photos.drop(slots).forEach(photoSourceProvider::release)
            val accepted = acceptedPhotos.mapIndexed { index, photo ->
                EditorMedia(
                    key = "local:${UUID.randomUUID()}",
                    localPhoto = photo,
                    mimeType = photo.mimeType,
                    isCover = state.media.isEmpty() && index == 0,
                )
            }
            val omittedForCapacity = inspected.photos.size - accepted.size
            _uiState.update {
                it.copy(
                    media = (it.media + accepted).ensureCover(),
                    isMediaDirty = it.isMediaDirty || accepted.isNotEmpty(),
                    isInspecting = false,
                    hasChanges = it.hasChanges || accepted.isNotEmpty(),
                    error = selectionMessage(inspected.rejections.map { rejection -> rejection.reason }, omittedForCapacity),
                )
            }
            persist()
        }
    }

    fun removeMedia(key: String) {
        change { state ->
            val item = state.media.firstOrNull { it.key == key } ?: return@change state
            item.localPhoto?.let(photoSourceProvider::release)
            state.copy(
                media = state.media.filterNot { it.key == key }.ensureCover(),
                deletedMediaIds = item.mediaId?.let { state.deletedMediaIds + it } ?: state.deletedMediaIds,
                isMediaDirty = true,
                error = null,
            )
        }
    }

    fun moveMedia(key: String, offset: Int) {
        change { state ->
            val from = state.media.indexOfFirst { it.key == key }
            val to = from + offset
            if (from !in state.media.indices || to !in state.media.indices) return@change state
            val reordered = state.media.toMutableList().apply {
                val item = removeAt(from)
                add(to, item)
            }
            state.copy(media = reordered, isMediaDirty = true, error = null)
        }
    }

    fun setCover(key: String) = change { state ->
        state.copy(
            media = state.media.map { it.copy(isCover = it.key == key) },
            isMediaDirty = true,
            error = null,
        )
    }

    fun onAltTextChange(key: String, value: String) = updateMedia(key) { it.copy(altText = value.take(MAX_ALT_LENGTH)) }

    fun onCaptionChange(key: String, value: String) = updateMedia(key) { it.copy(caption = value.take(MAX_CAPTION_LENGTH)) }

    private fun updateMedia(key: String, transform: (EditorMedia) -> EditorMedia) {
        change { state ->
            state.copy(
                media = state.media.map { if (it.key == key) transform(it) else it },
                isMediaDirty = true,
                error = null,
            )
        }
    }

    fun save() {
        val snapshot = _uiState.value
        if (snapshot.isSaving || snapshot.isInspecting || snapshot.isLoading || snapshot.loadError != null || snapshot.needsPhotoReview) return
        val title = snapshot.title.trim()
        val content = snapshot.content.trim()
        if (title.isBlank() || content.isBlank()) {
            _uiState.update { it.copy(error = "El título y el contenido no pueden estar vacíos.") }
            return
        }
        _uiState.update { it.copy(isSaving = true, hasChanges = true) }
        persist()
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null, uploadMessage = null) }
            if (!snapshot.isMediaDirty) {
                val result = if (workingPostId == null) {
                    repository.createPost(title, content, snapshot.status, snapshot.transition, snapshot.tags.takeIf { snapshot.tagsDirty }?.map { it.slug })
                } else {
                    repository.updatePost(requireNotNull(workingPostId), title, content, snapshot.status, snapshot.transition, snapshot.tags.takeIf { snapshot.tagsDirty }?.map { it.slug })
                }
                result.onSuccess { workingPostId = it.id; persist() }
                finish(result.exceptionOrNull())
                return@launch
            }

            // New uploads stay hidden until layout; keep the existing publication status.
            val draft = if (workingPostId == null) {
                repository.createPost(title, content, PostStatus.DRAFT, snapshot.transition, snapshot.tags.takeIf { snapshot.tagsDirty }?.map { it.slug })
            } else {
                repository.updatePostContent(requireNotNull(workingPostId), title, content, snapshot.transition, snapshot.tags.takeIf { snapshot.tagsDirty }?.map { it.slug })
            }
            val post = draft.getOrElse { error ->
                finish(error)
                return@launch
            }
            workingPostId = post.id
            persist()

            // Free confirmed removals before uploading replacements at the ten-photo limit.
            for (mediaId in _uiState.value.deletedMediaIds.toList()) {
                val deletion = mediaRepository.deleteMedia(post.id, mediaId)
                if (deletion.isFailure) {
                    finish(deletion.exceptionOrNull(), partial = true)
                    return@launch
                }
                _uiState.update { it.copy(deletedMediaIds = it.deletedMediaIds - mediaId) }
                persist()
            }

            val localToUpload = _uiState.value.media.filter { it.isLocal && it.mediaId == null }
            for ((index, item) in localToUpload.withIndex()) {
                _uiState.update {
                    it.copy(
                        uploadProgress = index to localToUpload.size,
                        uploadMessage = "Subiendo foto ${index + 1} de ${localToUpload.size}…",
                    )
                }
                val file = photoSourceProvider.uploadFile(requireNotNull(item.localPhoto))
                val upload = withContext(Dispatchers.IO) { mediaRepository.uploadPhoto(post.id, file) { intentId ->
                    // Persist the intent before transferring bytes: a process restart can clean it up.
                    _uiState.update { it.copy(deletedMediaIds = it.deletedMediaIds + intentId) }
                    persist()
                } }
                val mediaId = upload.getOrElse { error ->
                    // Evita que un intent fallido consuma uno de los diez huecos en el siguiente intento.
                    if (error is MediaUploadException) {
                        _uiState.update { it.copy(deletedMediaIds = it.deletedMediaIds + error.mediaId) }
                        persist()
                        if (mediaRepository.deleteMedia(post.id, error.mediaId).isSuccess) {
                            _uiState.update { it.copy(deletedMediaIds = it.deletedMediaIds - error.mediaId) }
                            persist()
                        }
                    }
                    finish(error, partial = true)
                    return@launch
                }
                _uiState.update { state ->
                    state.copy(
                        media = state.media.map { current ->
                            if (current.key == item.key) current.copy(mediaId = mediaId) else current
                        },
                        uploadProgress = (index + 1) to localToUpload.size,
                        deletedMediaIds = state.deletedMediaIds - mediaId,
                    )
                }
                persist()
            }

            val finalMedia = _uiState.value.media
            _uiState.update { it.copy(uploadMessage = "Guardando datos y orden de las fotos…") }
            for (item in finalMedia) {
                val mediaId = requireNotNull(item.mediaId)
                val metadata = mediaRepository.updateMetadata(post.id, mediaId, item.altText.trim(), item.caption.trim())
                if (metadata.isFailure) {
                    finish(metadata.exceptionOrNull(), partial = true)
                    return@launch
                }
            }
            val layout = mediaRepository.updateLayout(
                post.id,
                finalMedia.map { LayoutMedia(requireNotNull(it.mediaId), it.isCover) },
                _uiState.value.transition,
            )
            if (layout.isFailure) {
                finish(layout.exceptionOrNull(), partial = true)
                return@launch
            }
            _uiState.update { it.copy(uploadMessage = "Actualizando la publicación…") }
            finish(repository.updatePost(post.id, title, content, snapshot.status, snapshot.transition, snapshot.tags.takeIf { snapshot.tagsDirty }?.map { it.slug }).exceptionOrNull(), partial = true)
        }
    }

    private fun finish(error: Throwable?, partial: Boolean = false) {
        if (error == null) {
            try { draftStore?.clear() } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "Se guardó en el servidor, pero no se pudo eliminar la copia local. No vuelvas a guardar; sal del editor y descarta la copia al abrirlo.") }
                return
            }
            _uiState.value.media.mapNotNull { it.localPhoto }.forEach(photoSourceProvider::release)
        }
        _uiState.update {
            if (error == null) {
                it.copy(isSaving = false, uploadProgress = null, uploadMessage = null, isSaved = true, hasChanges = false)
            } else {
                it.copy(
                    isSaving = false,
                    uploadProgress = null,
                    uploadMessage = null,
                    error = if (partial) {
                        "El guardado quedó incompleto; algunos cambios ya están en el servidor. Tu copia local permite reintentar. ${ApiErrors.userMessage(error)}"
                    } else {
                        ApiErrors.userMessage(error)
                    },
                )
            }
        }
    }

    override fun onCleared() {
        _uiState.value.media.mapNotNull { it.localPhoto }.forEach(photoSourceProvider::release)
        super.onCleared()
    }

    private fun selectionMessage(
        rejections: List<PhotoRejectionReason>,
        omittedForCapacity: Int,
    ): String? {
        val messages = buildList {
            if (PhotoRejectionReason.FILE_TOO_LARGE in rejections) add("Algunas fotos superan el límite de entrada de 32 MiB.")
            if (PhotoRejectionReason.OPTIMIZATION_FAILED in rejections) add("No se pudo optimizar alguna foto al límite de 8 MiB. Prueba con una imagen más pequeña.")
            if (PhotoRejectionReason.EMPTY_FILE in rejections) add("Algunas fotos seleccionadas están vacías.")
            if (PhotoRejectionReason.UNSUPPORTED_IMAGE_FORMAT in rejections) {
                add("Algunas fotos usan un formato no admitido o tienen animación. Elige JPEG, PNG o WebP estáticos.")
            }
            if (PhotoRejectionReason.INVALID_IMAGE in rejections) {
                add("Algunas fotos no tienen dimensiones de imagen válidas.")
            }
            if (PhotoRejectionReason.SOURCE_UNREADABLE in rejections) {
                add("No se pudo leer alguna foto seleccionada.")
            }
            if (omittedForCapacity > 0) add("Solo se pueden añadir hasta 10 fotos.")
        }
        return messages.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun PostMedia.toEditorMedia() = EditorMedia(
        key = "server:$id",
        mediaId = id,
        kind = kind,
        mimeType = mime_type,
        altText = alt_text,
        caption = caption,
        isCover = is_cover,
    )

    private fun List<EditorMedia>.ensureCover(): List<EditorMedia> {
        if (isEmpty() || any { it.isCover }) return this
        return mapIndexed { index, item -> item.copy(isCover = index == 0) }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_ALT_LENGTH = 500
        const val MAX_CAPTION_LENGTH = 1000
        const val MAX_PHOTOS = 10
        const val READY_STATE = "ready"
    }
}
