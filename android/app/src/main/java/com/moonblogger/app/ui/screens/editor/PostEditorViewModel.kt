package com.moonblogger.app.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonblogger.app.data.media.InspectedPhoto
import com.moonblogger.app.data.media.LayoutMedia
import com.moonblogger.app.data.media.MediaRepository
import com.moonblogger.app.data.media.MediaUploadException
import com.moonblogger.app.data.media.PhotoSourceProvider
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
        get() = title.isNotBlank() && content.isNotBlank() && !isSaving && !isInspecting && !isLoading
}

/** Editor de posts y fotos: las cargas se completan antes de publicar. */
class PostEditorViewModel(
    private val repository: PostRepository,
    private val mediaRepository: MediaRepository,
    private val photoSourceProvider: PhotoSourceProvider,
    private val postId: Long?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostEditorUiState(isEdit = postId != null))
    val uiState: StateFlow<PostEditorUiState> = _uiState.asStateFlow()
    private var initialLoadStarted = false
    // Conserva el borrador creado durante un reintento mientras este ViewModel viva.
    // No se persiste: la reanudación tras finalizar el proceso es deuda explícita.
    private var workingPostId: Long? = postId

    fun start() {
        val id = postId ?: return
        if (initialLoadStarted) return
        initialLoadStarted = true
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getPost(id)
                .onSuccess { post ->
                    val media = post.media
                        .filter { it.state == READY_STATE }
                        .sortedWith(compareBy<PostMedia> { it.position ?: Int.MAX_VALUE }.thenBy { it.id })
                        .map { it.toEditorMedia() }
                        .ensureCover()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            title = post.title.take(MAX_TITLE_LENGTH),
                            content = post.content,
                            status = post.status,
                            transition = post.carousel_transition,
                            media = media,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = ApiErrors.userMessage(error)) }
                }
        }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value.take(MAX_TITLE_LENGTH), error = null) }

    fun onContentChange(value: String) = _uiState.update { it.copy(content = value, error = null) }

    fun onStatusChange(value: PostStatus) = _uiState.update { it.copy(status = value, error = null) }

    fun onTransitionChange(value: CarouselTransition) =
        _uiState.update { it.copy(transition = value, isMediaDirty = true, error = null) }

    /** Se invoca con URIs otorgados temporalmente por Photo Picker, nunca con permiso de galería. */
    fun onPhotosSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        var inspectionStarted = false
        _uiState.update { state ->
            if (state.isSaving || state.isInspecting) {
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
                    error = selectionMessage(inspected.rejections.map { rejection -> rejection.reason }, omittedForCapacity),
                )
            }
        }
    }

    fun removeMedia(key: String) {
        _uiState.update { state ->
            val item = state.media.firstOrNull { it.key == key } ?: return@update state
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
        _uiState.update { state ->
            val from = state.media.indexOfFirst { it.key == key }
            val to = from + offset
            if (from !in state.media.indices || to !in state.media.indices) return@update state
            val reordered = state.media.toMutableList().apply {
                val item = removeAt(from)
                add(to, item)
            }
            state.copy(media = reordered, isMediaDirty = true, error = null)
        }
    }

    fun setCover(key: String) = _uiState.update { state ->
        state.copy(
            media = state.media.map { it.copy(isCover = it.key == key) },
            isMediaDirty = true,
            error = null,
        )
    }

    fun onAltTextChange(key: String, value: String) = updateMedia(key) { it.copy(altText = value.take(MAX_ALT_LENGTH)) }

    fun onCaptionChange(key: String, value: String) = updateMedia(key) { it.copy(caption = value.take(MAX_CAPTION_LENGTH)) }

    private fun updateMedia(key: String, transform: (EditorMedia) -> EditorMedia) {
        _uiState.update { state ->
            state.copy(
                media = state.media.map { if (it.key == key) transform(it) else it },
                isMediaDirty = true,
                error = null,
            )
        }
    }

    fun save() {
        val snapshot = _uiState.value
        if (snapshot.isInspecting) return
        val title = snapshot.title.trim()
        val content = snapshot.content.trim()
        if (title.isBlank() || content.isBlank()) {
            _uiState.update { it.copy(error = "El título y el contenido no pueden estar vacíos.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null, uploadMessage = null) }
            if (!snapshot.isMediaDirty) {
                val result = if (postId == null) {
                    repository.createPost(title, content, snapshot.status, snapshot.transition)
                } else {
                    repository.updatePost(postId, title, content, snapshot.status, snapshot.transition)
                }
                finish(result.exceptionOrNull())
                return@launch
            }

            // El post queda en draft durante cualquier cambio de media: ningún asset pendiente puede publicarse.
            val draft = if (workingPostId == null) {
                repository.createPost(title, content, PostStatus.DRAFT, snapshot.transition)
            } else {
                repository.updatePost(
                    requireNotNull(workingPostId),
                    title,
                    content,
                    PostStatus.DRAFT,
                    snapshot.transition,
                )
            }
            val post = draft.getOrElse { error ->
                finish(error)
                return@launch
            }
            workingPostId = post.id

            val localToUpload = _uiState.value.media.filter { it.isLocal && it.mediaId == null }
            for ((index, item) in localToUpload.withIndex()) {
                _uiState.update {
                    it.copy(
                        uploadProgress = index to localToUpload.size,
                        uploadMessage = "Subiendo foto ${index + 1} de ${localToUpload.size}…",
                    )
                }
                val file = photoSourceProvider.uploadFile(requireNotNull(item.localPhoto))
                val upload = withContext(Dispatchers.IO) { mediaRepository.uploadPhoto(post.id, file) }
                val mediaId = upload.getOrElse { error ->
                    // Evita que un intent fallido consuma uno de los diez huecos en el siguiente intento.
                    if (error is MediaUploadException) mediaRepository.deleteMedia(post.id, error.mediaId)
                    finish(error, keptAsDraft = true)
                    return@launch
                }
                _uiState.update { state ->
                    state.copy(
                        media = state.media.map { current ->
                            if (current.key == item.key) current.copy(mediaId = mediaId) else current
                        },
                        uploadProgress = (index + 1) to localToUpload.size,
                    )
                }
            }

            val finalMedia = _uiState.value.media
            _uiState.update { it.copy(uploadMessage = "Guardando datos y orden de las fotos…") }
            for (item in finalMedia) {
                val mediaId = requireNotNull(item.mediaId)
                val metadata = mediaRepository.updateMetadata(post.id, mediaId, item.altText.trim(), item.caption.trim())
                if (metadata.isFailure) {
                    finish(metadata.exceptionOrNull(), keptAsDraft = true)
                    return@launch
                }
            }
            for (mediaId in _uiState.value.deletedMediaIds) {
                val deletion = mediaRepository.deleteMedia(post.id, mediaId)
                if (deletion.isFailure) {
                    finish(deletion.exceptionOrNull(), keptAsDraft = true)
                    return@launch
                }
            }
            val layout = mediaRepository.updateLayout(
                post.id,
                finalMedia.map { LayoutMedia(requireNotNull(it.mediaId), it.isCover) },
                _uiState.value.transition,
            )
            if (layout.isFailure) {
                finish(layout.exceptionOrNull(), keptAsDraft = true)
                return@launch
            }
            _uiState.update { it.copy(uploadMessage = "Actualizando la publicación…") }
            finish(repository.updatePost(post.id, title, content, snapshot.status, snapshot.transition).exceptionOrNull())
        }
    }

    private fun finish(error: Throwable?, keptAsDraft: Boolean = false) {
        if (error == null) photoSourceProvider.clearTemporaryFiles()
        _uiState.update {
            if (error == null) {
                it.copy(isSaving = false, uploadProgress = null, uploadMessage = null, isSaved = true)
            } else {
                it.copy(
                    isSaving = false,
                    uploadProgress = null,
                    uploadMessage = null,
                    error = if (keptAsDraft) {
                        "No se completaron las fotos; la publicación se guardó como borrador. ${ApiErrors.userMessage(error)}"
                    } else {
                        ApiErrors.userMessage(error)
                    },
                )
            }
        }
    }

    override fun onCleared() {
        photoSourceProvider.clearTemporaryFiles()
        super.onCleared()
    }

    private fun selectionMessage(
        rejections: List<PhotoRejectionReason>,
        omittedForCapacity: Int,
    ): String? {
        val messages = buildList {
            if (PhotoRejectionReason.FILE_TOO_LARGE in rejections) add("Algunas fotos superan el límite de 8 MiB.")
            if (PhotoRejectionReason.EMPTY_FILE in rejections) add("Algunas fotos seleccionadas están vacías.")
            if (PhotoRejectionReason.UNSUPPORTED_IMAGE_FORMAT in rejections) {
                add("Algunas fotos no son JPEG, PNG ni WebP válidas.")
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
