package com.moonblogger.app.data.repository

import com.moonblogger.app.data.model.Post
import com.moonblogger.app.data.model.CarouselTransition
import com.moonblogger.app.data.model.PostRequest
import com.moonblogger.app.data.model.PostStatus
import com.moonblogger.app.data.model.MediaReadUrlsResponse
import com.moonblogger.app.data.remote.PostsApi
import com.moonblogger.app.data.remote.safeApiCall

/**
 * Acceso a los posts privados (autenticado).
 *
 * Paginación DRF por número de página; los enlaces next nunca reciben tokens directamente.
 */
class PostRepository(private val postsApi: PostsApi) {

    suspend fun listTags(): Result<List<com.moonblogger.app.data.model.Tag>> = safeApiCall { postsApi.listTags() }

    suspend fun listPosts(): Result<List<Post>> =
        safeApiCall { postsApi.listPosts(status = null).results }

    suspend fun listPostPage(page: Int = 1): Result<com.moonblogger.app.data.model.PostPage> =
        safeApiCall { postsApi.listPosts(status = null, page = page) }

    suspend fun getPost(id: Long): Result<Post> =
        safeApiCall { postsApi.getPost(id) }

    suspend fun getMediaReadUrls(id: Long): Result<MediaReadUrlsResponse> =
        safeApiCall { postsApi.getMediaReadUrls(id) }

    suspend fun createPost(
        title: String,
        content: String,
        status: PostStatus,
        transition: CarouselTransition = CarouselTransition.SLIDE,
        tagSlugs: List<String>? = null,
    ): Result<Post> =
        safeApiCall { postsApi.createPost(PostRequest(title, content, status, transition, tagSlugs)) }

    suspend fun updatePost(
        id: Long,
        title: String,
        content: String,
        status: PostStatus,
        transition: CarouselTransition = CarouselTransition.SLIDE,
        tagSlugs: List<String>? = null,
    ): Result<Post> =
        safeApiCall { postsApi.updatePost(id, PostRequest(title, content, status, transition, tagSlugs)) }

    /** Metadata preparation must not change publication state, including after a lost response. */
    suspend fun updatePostContent(
        id: Long, title: String, content: String, transition: CarouselTransition, tagSlugs: List<String>? = null,
    ): Result<Post> = safeApiCall { postsApi.updatePost(id, PostRequest(title, content, carousel_transition = transition, tag_slugs = tagSlugs)) }

    suspend fun deletePost(id: Long): Result<Unit> =
        safeApiCall { postsApi.deletePost(id) }
}
