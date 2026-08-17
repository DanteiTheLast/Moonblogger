package com.moonblogger.app.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonblogger.app.data.model.Post
import com.moonblogger.app.data.model.MediaReadUrl
import com.moonblogger.app.data.remote.ApiErrors
import com.moonblogger.app.data.repository.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostDetailUiState(
    val isLoading: Boolean = true,
    val post: Post? = null,
    val error: String? = null,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val mediaReadUrls: List<MediaReadUrl> = emptyList(),
    val mediaError: String? = null,
)

class PostDetailViewModel(
    private val repository: PostRepository,
    private val postId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()

    /**
     * Carga el post. Se llama desde LaunchedEffect(Unit): al volver del
     * editor, la pantalla se recompone y se recarga para reflejar los cambios.
     */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getPost(postId)
                .onSuccess { post ->
                    _uiState.update { it.copy(isLoading = false, post = post) }
                    if (post.media.any { it.state == "ready" }) {
                        repository.getMediaReadUrls(postId).onSuccess { response ->
                            _uiState.update { it.copy(mediaReadUrls = response.media, mediaError = null) }
                        }.onFailure { e ->
                            _uiState.update { it.copy(mediaError = ApiErrors.userMessage(e)) }
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ApiErrors.userMessage(e))
                    }
                }
        }
    }

    fun delete() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, error = null) }
            repository.deletePost(postId)
                .onSuccess {
                    _uiState.update { it.copy(isDeleting = false, isDeleted = true) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isDeleting = false, error = ApiErrors.userMessage(e))
                    }
                }
        }
    }
}
