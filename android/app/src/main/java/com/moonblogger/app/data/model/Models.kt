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

@Serializable
data class PostRequest(
    val title: String,
    val content: String,
    val status: PostStatus,
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
)

/** Lista paginada de DRF: {count, next, previous, results}. */
@Serializable
data class PostPage(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<Post> = emptyList(),
)
