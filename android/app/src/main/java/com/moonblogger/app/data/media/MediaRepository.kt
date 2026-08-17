package com.moonblogger.app.data.media

import com.moonblogger.app.data.model.CarouselTransition
import com.moonblogger.app.data.model.CompleteMediaRequest
import com.moonblogger.app.data.model.MediaLayoutItem
import com.moonblogger.app.data.model.MediaLayoutRequest
import com.moonblogger.app.data.model.MediaMetadataRequest
import com.moonblogger.app.data.model.UploadIntentRequest
import com.moonblogger.app.data.remote.PostsApi
import com.moonblogger.app.data.remote.safeApiCall
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Archivo de imagen listo para transferirse, sin conservar ninguna URL firmada. */
data class UploadFile(
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val openStream: () -> java.io.InputStream,
)

/** Permite limpiar el intent creado si la carga o complete no llegan a terminar. */
class MediaUploadException(val mediaId: String, cause: Throwable) : IOException(cause.message, cause)

/**
 * Orquesta las rutas autenticadas de media. La URL firmada solo existe en la
 * variable local de [uploadPhoto] durante el PUT y nunca pasa al estado de UI.
 */
class MediaRepository(
    private val postsApi: PostsApi,
    private val signedUrlUploader: SignedUrlUploader,
) {
    suspend fun uploadPhoto(postId: Long, file: UploadFile): Result<String> = safeApiCall {
        val intent = postsApi.createUploadIntent(
            postId,
            UploadIntentRequest(
                kind = "image",
                mime_type = file.mimeType,
                size_bytes = file.sizeBytes,
                width = file.width,
                height = file.height,
            ),
        )
        try {
            signedUrlUploader.put(intent.upload_url, file)
            postsApi.completeMedia(postId, CompleteMediaRequest(intent.media_id)).media_id
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw MediaUploadException(intent.media_id, error)
        }
    }

    suspend fun updateMetadata(postId: Long, mediaId: String, altText: String, caption: String): Result<Unit> =
        safeApiCall {
            postsApi.updateMediaMetadata(postId, mediaId, MediaMetadataRequest(altText, caption))
        }

    suspend fun deleteMedia(postId: Long, mediaId: String): Result<Unit> =
        safeApiCall { postsApi.deleteMedia(postId, mediaId) }

    suspend fun updateLayout(
        postId: Long,
        media: List<LayoutMedia>,
        transition: CarouselTransition,
    ): Result<Unit> = safeApiCall<Unit> {
        postsApi.updateMediaLayout(
            postId,
            MediaLayoutRequest(
                items = media.mapIndexed { position, item ->
                    MediaLayoutItem(item.id, position, item.isCover)
                },
                carousel_transition = transition,
            ),
        )
    }
}

data class LayoutMedia(val id: String, val isCover: Boolean)
