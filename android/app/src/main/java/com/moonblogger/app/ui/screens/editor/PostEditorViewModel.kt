package com.moonblogger.app.ui.screens.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonblogger.app.data.model.PostStatus
import com.moonblogger.app.data.remote.ApiErrors
import com.moonblogger.app.data.repository.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostEditorUiState(
    val isEdit: Boolean = false,
    val isLoading: Boolean = false,
    val title: String = "",
    val content: String = "",
    val status: PostStatus = PostStatus.DRAFT,
    val isSaving: Boolean = false,
    val error: String? = null,
    val isSaved: Boolean = false,
) {
    val canSubmit: Boolean
        get() = title.isNotBlank() && content.isNotBlank() && !isSaving && !isLoading
}

/**
 * Crea (postId == null) o edita (postId != null) una publicación.
 * En modo edición precarga los datos con [start]. El estado por defecto es
 * DRAFT (decisión D3).
 */
class PostEditorViewModel(
    private val repository: PostRepository,
    private val postId: Long?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostEditorUiState(isEdit = postId != null))
    val uiState: StateFlow<PostEditorUiState> = _uiState.asStateFlow()

    private var initialLoadStarted = false

    fun start() {
        val id = postId ?: return
        if (initialLoadStarted) return
        initialLoadStarted = true
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getPost(id)
                .onSuccess { post ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            title = post.title.take(MAX_TITLE_LENGTH),
                            content = post.content,
                            status = post.status,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ApiErrors.userMessage(e))
                    }
                }
        }
    }

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value.take(MAX_TITLE_LENGTH), error = null) }
    }

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, error = null) }
    }

    fun onStatusChange(value: PostStatus) {
        _uiState.update { it.copy(status = value, error = null) }
    }

    fun save() {
        val title = _uiState.value.title.trim()
        val content = _uiState.value.content.trim()
        val status = _uiState.value.status
        if (title.isBlank() || content.isBlank()) {
            _uiState.update { it.copy(error = "El título y el contenido no pueden estar vacíos.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            val result = if (postId == null) {
                repository.createPost(title, content, status)
            } else {
                repository.updatePost(postId, title, content, status)
            }
            result
                .onSuccess {
                    _uiState.update { it.copy(isSaving = false, isSaved = true) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isSaving = false, error = ApiErrors.userMessage(e))
                    }
                }
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 200
    }
}
