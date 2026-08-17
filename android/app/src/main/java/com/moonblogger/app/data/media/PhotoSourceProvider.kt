package com.moonblogger.app.data.media

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

data class InspectedPhoto(
    /** Copia privada y estable; no conserva una URI concedida temporalmente por el proveedor. */
    val localFile: File,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
) {
    /** Coil acepta esta file URI privada para la vista previa. */
    val uri: String get() = Uri.fromFile(localFile).toString()
}

data class PhotoSelectionResult(
    val photos: List<InspectedPhoto>,
    val rejections: List<PhotoRejection>,
) {
    val rejectedCount: Int get() = rejections.size
}

data class PhotoRejection(
    val reason: PhotoRejectionReason,
)

/** Motivos que el ViewModel traduce a mensajes seguros para UI. */
enum class PhotoRejectionReason {
    EMPTY_FILE,
    FILE_TOO_LARGE,
    UNSUPPORTED_IMAGE_FORMAT,
    INVALID_IMAGE,
    SOURCE_UNREADABLE,
}

/**
 * Convierte cada URI concedida por Android en una copia temporal privada antes
 * de inspeccionarla. La copia evita depender de permisos efímeros de Archivos
 * durante previsualización, subida o reintentos.
 *
 * Los temporales se mantienen mientras el editor vive; cacheDir puede perderse
 * tras process death, por lo que el borrador local no se puede reanudar entonces.
 */
class PhotoSourceProvider(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
) {
    private val temporaryFiles = mutableSetOf<File>()
    private val temporaryFilesLock = Any()

    fun inspect(uris: List<Uri>): PhotoSelectionResult {
        val accepted = mutableListOf<InspectedPhoto>()
        val rejections = mutableListOf<PhotoRejection>()
        uris.forEach { uri ->
            when (val result = inspectUri(uri)) {
                is PhotoInspection.Accepted -> accepted += result.photo
                is PhotoInspection.Rejected -> rejections += PhotoRejection(result.reason)
            }
        }
        return PhotoSelectionResult(accepted, rejections)
    }

    fun uploadFile(photo: InspectedPhoto): UploadFile = UploadFile(
        mimeType = photo.mimeType,
        sizeBytes = photo.sizeBytes,
        width = photo.width,
        height = photo.height,
        openStream = {
            photo.localFile.inputStream()
        },
    )

    /** Borra una copia que ya no pertenece al editor, por ejemplo al quitar una foto. */
    fun release(photo: InspectedPhoto) = deleteTemporaryFile(photo.localFile)

    /** Borra todas las copias del editor al terminar correctamente o destruir su ViewModel. */
    fun clearTemporaryFiles() {
        synchronized(temporaryFilesLock) {
            temporaryFiles.removeAll { file -> file.delete() || !file.exists() }
        }
    }

    private fun inspectUri(uri: Uri): PhotoInspection {
        // Algunos proveedores devuelven image/jpg o image/x-png. Es metadata
        // auxiliar, no una condición de aceptación: la firma manda.
        runCatching { normalizePhotoMimeType(contentResolver.getType(uri)) }

        val copy = try {
            copyToTemporaryFile(uri)
        } catch (_: Exception) {
            return PhotoInspection.Rejected(PhotoRejectionReason.SOURCE_UNREADABLE)
        }
        val temporaryFile = when (copy) {
            is TemporaryCopyResult.TooLarge -> return PhotoInspection.Rejected(PhotoRejectionReason.FILE_TOO_LARGE)
            is TemporaryCopyResult.Unreadable -> return PhotoInspection.Rejected(PhotoRejectionReason.SOURCE_UNREADABLE)
            is TemporaryCopyResult.Copied -> copy
        }

        val file = temporaryFile.file
        if (temporaryFile.sizeBytes == 0L) {
            deleteTemporaryFile(file)
            return PhotoInspection.Rejected(PhotoRejectionReason.EMPTY_FILE)
        }
        val format = try {
            file.inputStream().use(::detectPhotoFormat)
        } catch (_: Exception) {
            null
        }
        if (format == null) {
            deleteTemporaryFile(file)
            return PhotoInspection.Rejected(PhotoRejectionReason.UNSUPPORTED_IMAGE_FORMAT)
        }
        val dimensions = dimensions(file)
        if (dimensions == null) {
            deleteTemporaryFile(file)
            return PhotoInspection.Rejected(PhotoRejectionReason.INVALID_IMAGE)
        }
        return PhotoInspection.Accepted(
            InspectedPhoto(
                localFile = file,
                mimeType = format.mimeType,
                sizeBytes = temporaryFile.sizeBytes,
                width = dimensions.first,
                height = dimensions.second,
            ),
        )
    }

    /** Abre la URI de origen una sola vez y transfiere sus bytes por bloques. */
    private fun copyToTemporaryFile(uri: Uri): TemporaryCopyResult = synchronized(temporaryFilesLock) {
        val directory = File(cacheDir, TEMP_DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) return@synchronized TemporaryCopyResult.Unreadable
        val file = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, directory)
        temporaryFiles += file
        try {
            val result = contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().buffered().use { output -> copyPhotoStream(input, output) }
            } ?: return@synchronized deleteAndReturn(file, TemporaryCopyResult.Unreadable)
            when (result) {
                is PhotoCopyResult.Copied -> TemporaryCopyResult.Copied(file, result.bytes)
                PhotoCopyResult.ExceedsLimit -> deleteAndReturn(file, TemporaryCopyResult.TooLarge)
            }
        } catch (_: Exception) {
            deleteAndReturn(file, TemporaryCopyResult.Unreadable)
        }
    }

    private fun deleteAndReturn(file: File, result: TemporaryCopyResult): TemporaryCopyResult {
        if (file.delete() || !file.exists()) temporaryFiles.remove(file)
        return result
    }

    private fun deleteTemporaryFile(file: File) {
        synchronized(temporaryFilesLock) {
            if (file.delete() || !file.exists()) temporaryFiles.remove(file)
        }
    }

    private fun dimensions(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private sealed interface PhotoInspection {
        data class Accepted(val photo: InspectedPhoto) : PhotoInspection

        data class Rejected(val reason: PhotoRejectionReason) : PhotoInspection
    }

    private sealed interface TemporaryCopyResult {
        data class Copied(val file: File, val sizeBytes: Long) : TemporaryCopyResult

        data object TooLarge : TemporaryCopyResult

        data object Unreadable : TemporaryCopyResult
    }

    private companion object {
        const val TEMP_DIRECTORY_NAME = "moonblogger-photos"
        const val TEMP_FILE_PREFIX = "photo-"
        const val TEMP_FILE_SUFFIX = ".upload"
    }
}
