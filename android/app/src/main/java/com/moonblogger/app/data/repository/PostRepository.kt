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
 * NOTA DE PAGINACIÓN (v1): el endpoint devuelve una lista paginada de DRF
 * (`{count, next, previous, results}`, page_size 20). En esta versión solo se
 * consume la primera página. La gestión de `next`/carga infinita queda como
 * trabajo futuro documentado en `android/README.md`.
 */
class PostRepository(private val postsApi: PostsApi) {

    suspend fun listPosts(): Result<List<Post>> =
        safeApiCall { postsApi.listPosts(status = null).results }

    suspend fun getPost(id: Long): Result<Post> =
        safeApiCall { postsApi.getPost(id) }

    suspend fun getMediaReadUrls(id: Long): Result<MediaReadUrlsResponse> =
        safeApiCall { postsApi.getMediaReadUrls(id) }

    suspend fun createPost(
        title: String,
        content: String,
        status: PostStatus,
        transition: CarouselTransition = CarouselTransition.SLIDE,
    ): Result<Post> =
        safeApiCall { postsApi.createPost(PostRequest(title, content, status, transition)) }

    suspend fun updatePost(
        id: Long,
        title: String,
        content: String,
        status: PostStatus,
        transition: CarouselTransition = CarouselTransition.SLIDE,
    ): Result<Post> =
        safeApiCall { postsApi.updatePost(id, PostRequest(title, content, status, transition)) }

    suspend fun deletePost(id: Long): Result<Unit> =
        safeApiCall { postsApi.deletePost(id) }
}
