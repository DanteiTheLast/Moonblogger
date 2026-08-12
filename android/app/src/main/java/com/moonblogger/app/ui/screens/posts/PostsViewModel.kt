package com.moonblogger.app.ui.screens.posts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonblogger.app.data.model.Post
import com.moonblogger.app.data.remote.ApiErrors
import com.moonblogger.app.data.repository.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val posts: List<Post> = emptyList(),
    val error: String? = null,
)

/** Lista de posts (incluye borradores) con pull-to-refresh. */
class PostsViewModel(
    private val repository: PostRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PostsUiState())
    val uiState: StateFlow<PostsUiState> = _uiState.asStateFlow()

    private var initialLoadStarted = false

    /** Carga inicial; se ejecuta una vez por instancia (al entrar en pantalla). */
    fun start() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        load()
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            repository.listPosts()
                .onSuccess { posts ->
                    _uiState.update { it.copy(isRefreshing = false, posts = posts) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isRefreshing = false, error = ApiErrors.userMessage(e))
                    }
                }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.listPosts()
                .onSuccess { posts ->
                    _uiState.update { it.copy(isLoading = false, posts = posts) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ApiErrors.userMessage(e))
                    }
                }
        }
    }
}
