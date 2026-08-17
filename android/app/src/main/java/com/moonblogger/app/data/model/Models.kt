package com.moonblogger.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs del contrato de API (docs/api.md).
 *
 * Los nombres de campo se mantienen en snake_case, igual que el JSON de la API,
 * para reflejar el contrato sin ruido de anotaciones.
 */

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refresh: String,
)

@Serializable
data class TokenResponse(
    val access: String,
    val refresh: String,
)

/** Estado de publicación (D3). Coincide con los values del backend. */
@Serializable
enum class PostStatus {
    @SerialName("draft")
    DRAFT,

    @SerialName("published")
    PUBLISHED,
}

/** Transición del carrusel admitida por la API. */
@Serializable
enum class CarouselTransition {
    @SerialName("slide")
    SLIDE,

    @SerialName("fade")
    FADE,

    @SerialName("bubble")
    BUBBLE,

    @SerialName("none")
    NONE,
}

@Serializable
data class PostRequest(
    val title: String,
    val content: String,
    val status: PostStatus,
    val carousel_transition: CarouselTransition = CarouselTransition.SLIDE,
)

@Serializable
data class PostMedia(
    val id: String,
    val kind: String,
    val state: String,
    val position: Int? = null,
    val is_cover: Boolean = false,
    val mime_type: String,
    val size_bytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val duration_seconds: Int? = null,
    val alt_text: String = "",
    val caption: String = "",
)

/** Objeto Post completo (serializador del backend). */
@Serializable
data class Post(
    val id: Long,
    val slug: String,
    val title: String,
    val content: String,
    val status: PostStatus,
    val created_at: String,
    val updated_at: String,
    val published_at: String? = null,
    val carousel_transition: CarouselTransition = CarouselTransition.SLIDE,
    val media: List<PostMedia> = emptyList(),
)

/** Lista paginada de DRF: {count, next, previous, results}. */
@Serializable
data class PostPage(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<Post> = emptyList(),
)

/** DTOs exclusivos del protocolo de multimedia privada. */
@Serializable
data class UploadIntentRequest(
    val kind: String,
    val mime_type: String,
    val size_bytes: Long,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class UploadIntentResponse(
    val media_id: String,
    val upload_url: String,
    val expires_at: String,
)

@Serializable
data class CompleteMediaRequest(val media_id: String)

@Serializable
data class CompleteMediaResponse(val media_id: String, val state: String)

@Serializable
data class MediaMetadataRequest(val alt_text: String, val caption: String)

@Serializable
data class MediaLayoutItem(val id: String, val position: Int, val is_cover: Boolean)

@Serializable
data class MediaLayoutRequest(
    val items: List<MediaLayoutItem>,
    val carousel_transition: CarouselTransition,
)
