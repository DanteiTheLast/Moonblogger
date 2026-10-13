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
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val posts: List<Post> = emptyList(),
    val error: String? = null,
)

/** Lista privada paginada. Los fallos al cargar más conservan la página y permiten reintentar. */
class PostsViewModel(private val repository: PostRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(PostsUiState())
    val uiState: StateFlow<PostsUiState> = _uiState.asStateFlow()
    private var hasLoaded = false
    private var activeRequest: Job? = null
    private var nextPage = 2

    fun onScreenResumed() = load(append = false, initial = !hasLoaded)
    fun refresh() = load(append = false, initial = false)
    fun loadMore() {
        if (_uiState.value.hasMore) load(append = true, initial = false)
    }

    private fun load(append: Boolean, initial: Boolean) {
        if (activeRequest?.isActive == true) return
        activeRequest = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = initial, isRefreshing = !append && !initial,
                isLoadingMore = append, error = null) }
            val page = if (append) nextPage else 1
            repository.listPostPage(page)
                .onSuccess { response ->
                    hasLoaded = true
                    nextPage = page + 1
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false,
                        hasMore = response.next != null,
                        posts = (if (append) it.posts + response.results else response.results).distinctBy { post -> post.id }) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false,
                        error = ApiErrors.userMessage(error)) }
                }
        }.also { job -> job.invokeOnCompletion { if (activeRequest === job) activeRequest = null } }
    }
}
