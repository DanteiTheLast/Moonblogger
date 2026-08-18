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
import kotlinx.coroutines.Job

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

    private var hasLoaded = false
    private var activeRequest: Job? = null

    /** Invocado al volver a estar visible: carga inicial o refresh silencioso. */
    fun onScreenResumed() {
        if (activeRequest?.isActive == true) return
        val initial = !hasLoaded
        activeRequest = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = initial,
                    isRefreshing = !initial,
                    error = null,
                )
            }
            repository.listPosts()
                .onSuccess { posts ->
                    hasLoaded = true
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, posts = posts)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = ApiErrors.userMessage(e),
                        )
                    }
                }
        }.also { job ->
            job.invokeOnCompletion { if (activeRequest === job) activeRequest = null }
        }
    }

    fun refresh() {
        if (activeRequest?.isActive == true) return
        activeRequest = viewModelScope.launch {
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
        }.also { job ->
            job.invokeOnCompletion { if (activeRequest === job) activeRequest = null }
        }
    }
}
