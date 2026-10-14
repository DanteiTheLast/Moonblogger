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
    val coverUrls: Map<Long, String> = emptyMap(),
    val coverErrors: Set<Long> = emptySet(),
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

    private val coversLoading = mutableMapOf<Long, Int>()
    private val coverExpirations = mutableMapOf<Long, Long>()
    private var coverGeneration = 0
    fun loadCover(post: Post, retry: Boolean = false) {
        val cover = post.media.firstOrNull { it.is_cover && it.state == "ready" && it.kind == "image" } ?: return
        if (!retry && post.id in _uiState.value.coverErrors) return
        if (!retry && _uiState.value.coverUrls.containsKey(post.id) && (coverExpirations[post.id] ?: 0) > System.currentTimeMillis() + 30_000) return
        if (coversLoading[post.id] == coverGeneration) return
        val generation = coverGeneration
        coversLoading[post.id] = generation
        viewModelScope.launch {
            try {
                repository.getMediaReadUrls(post.id).onSuccess { response ->
                    if (generation != coverGeneration || _uiState.value.posts.none { it.id == post.id && it.updated_at == post.updated_at }) return@onSuccess
                    val media = response.media.firstOrNull { it.id == cover.id } ?: return@onSuccess
                    coverExpirations[post.id] = runCatching { java.time.Instant.parse(response.expires_at).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 60_000)
                    _uiState.update { it.copy(coverUrls = it.coverUrls + (post.id to media.url), coverErrors = it.coverErrors - post.id) }
                }.onFailure {
                    if (generation == coverGeneration) _uiState.update { it.copy(coverErrors = it.coverErrors + post.id) }
                }
            } finally { if (coversLoading[post.id] == generation) coversLoading.remove(post.id) }
        }
    }
    fun coverFailed(post: Post) { _uiState.update { it.copy(coverErrors = it.coverErrors + post.id) } }

    private fun load(append: Boolean, initial: Boolean) {
        val current = _uiState.value
        if (activeRequest?.isActive == true && (current.isLoading || current.isLoadingMore || current.isRefreshing)) return
        activeRequest = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = initial, isRefreshing = !append && !initial,
                isLoadingMore = append, error = null) }
            if (!append) { coverGeneration++; coverExpirations.clear() }
            val page = if (append) nextPage else 1
            repository.listPostPage(page)
                .onSuccess { response ->
                    hasLoaded = true
                    nextPage = page + 1
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false,
                        hasMore = response.next != null,
                        coverUrls = if (append) it.coverUrls else emptyMap(),
                        coverErrors = if (append) it.coverErrors else emptySet(),
                        posts = (if (append) it.posts + response.results else response.results).distinctBy { post -> post.id }) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false,
                        error = ApiErrors.userMessage(error)) }
                }
        }.also { job -> job.invokeOnCompletion { if (activeRequest === job) activeRequest = null } }
    }
}
