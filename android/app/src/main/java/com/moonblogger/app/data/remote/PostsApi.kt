package com.moonblogger.app.data.remote

import com.moonblogger.app.data.model.Post
import com.moonblogger.app.data.model.PostPage
import com.moonblogger.app.data.model.PostRequest
import com.moonblogger.app.data.model.CompleteMediaRequest
import com.moonblogger.app.data.model.CompleteMediaResponse
import com.moonblogger.app.data.model.MediaLayoutRequest
import com.moonblogger.app.data.model.MediaMetadataRequest
import com.moonblogger.app.data.model.UploadIntentRequest
import com.moonblogger.app.data.model.UploadIntentResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.PUT

/**
 * Endpoints privados de posts (solo autenticado). El header
 * `Authorization: Bearer` lo añade [com.moonblogger.app.network.AuthInterceptor].
 */
interface PostsApi {

    @GET("api/v1/posts/")
    suspend fun listPosts(
        @Query("status") status: String? = null,
    ): PostPage

    @GET("api/v1/posts/{id}/")
    suspend fun getPost(@Path("id") id: Long): Post

    @POST("api/v1/posts/")
    suspend fun createPost(@Body body: PostRequest): Post

    @PATCH("api/v1/posts/{id}/")
    suspend fun updatePost(
        @Path("id") id: Long,
        @Body body: PostRequest,
    ): Post

    /** DELETE → 204 sin cuerpo. Retrofit 2.10+ soporta Unit como tipo de respuesta. */
    @DELETE("api/v1/posts/{id}/")
    suspend fun deletePost(@Path("id") id: Long)

    @POST("api/v1/posts/{id}/media/upload-intents/")
    suspend fun createUploadIntent(
        @Path("id") postId: Long,
        @Body body: UploadIntentRequest,
    ): UploadIntentResponse

    @POST("api/v1/posts/{id}/media/complete/")
    suspend fun completeMedia(
        @Path("id") postId: Long,
        @Body body: CompleteMediaRequest,
    ): CompleteMediaResponse

    @PATCH("api/v1/posts/{id}/media/{mediaId}/")
    suspend fun updateMediaMetadata(
        @Path("id") postId: Long,
        @Path("mediaId") mediaId: String,
        @Body body: MediaMetadataRequest,
    )

    @DELETE("api/v1/posts/{id}/media/{mediaId}/")
    suspend fun deleteMedia(
        @Path("id") postId: Long,
        @Path("mediaId") mediaId: String,
    )

    @PUT("api/v1/posts/{id}/media/layout/")
    suspend fun updateMediaLayout(
        @Path("id") postId: Long,
        @Body body: MediaLayoutRequest,
    ): Post
}
