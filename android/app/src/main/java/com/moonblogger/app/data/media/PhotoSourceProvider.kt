package com.moonblogger.app.data.media

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

const val MAX_PHOTO_BYTES = 8L * 1024 * 1024

data class InspectedPhoto(
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

data class PhotoSelectionResult(
    val photos: List<InspectedPhoto>,
    val rejectedCount: Int,
)

/** Lee exclusivamente URIs concedidos por Android Photo Picker; no pide permisos de galería. */
class PhotoSourceProvider(private val contentResolver: ContentResolver) {
    fun inspect(uris: List<Uri>): PhotoSelectionResult {
        val accepted = mutableListOf<InspectedPhoto>()
        var rejected = 0
        uris.forEach { uri ->
            try {
                val mimeType = contentResolver.getType(uri)?.lowercase()
                val sizeBytes = uriSize(uri)
                val dimensions = dimensions(uri)
                if (
                    mimeType == null || mimeType !in SUPPORTED_MIME_TYPES ||
                    sizeBytes == null || sizeBytes !in 1L..MAX_PHOTO_BYTES ||
                    dimensions == null
                ) {
                    rejected++
                } else {
                    accepted += InspectedPhoto(
                        uri.toString(),
                        mimeType,
                        sizeBytes,
                        dimensions.first,
                        dimensions.second,
                    )
                }
            } catch (_: Exception) {
                rejected++
            }
        }
        return PhotoSelectionResult(accepted, rejected)
    }

    fun uploadFile(photo: InspectedPhoto): UploadFile = UploadFile(
        mimeType = photo.mimeType,
        sizeBytes = photo.sizeBytes,
        width = photo.width,
        height = photo.height,
        openStream = {
            contentResolver.openInputStream(Uri.parse(photo.uri))
                ?: throw IOException("No se pudo abrir la imagen seleccionada.")
        },
    )

    private fun uriSize(uri: Uri): Long? {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) return descriptor.length
        }
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor?.moveToFirst() == true && !cursor.isNull(0)) cursor.getLong(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun dimensions(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
