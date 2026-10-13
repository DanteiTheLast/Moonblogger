package com.moonblogger.app.data.media

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

const val MAX_PHOTO_INPUT_BYTES = 32L * 1024 * 1024
const val MAX_PHOTO_SIDE = 2560
const val MAX_SOURCE_PHOTO_PIXELS = 100_000_000L

const val MAX_PHOTO_BYTES = 8L * 1024 * 1024

/** El resultado evita confundir un stream que supera el límite con uno ilegible. */
internal sealed interface PhotoStreamSize {
    data class Exact(val bytes: Long) : PhotoStreamSize

    data object ExceedsLimit : PhotoStreamSize
}

/**
 * Cuenta un stream en bloques y se detiene tan pronto como excede [maxBytes].
 * No conserva el contenido de la foto en memoria.
 */
internal fun countPhotoStreamBytes(
    input: InputStream,
    maxBytes: Long = MAX_PHOTO_BYTES,
): PhotoStreamSize {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) return PhotoStreamSize.Exact(total)
        total += read
        if (total > maxBytes) return PhotoStreamSize.ExceedsLimit
    }
}

/** Formatos que el cliente puede transferir al backend. */
internal enum class PhotoFormat(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
}

/**
 * Identifica la imagen por la cabecera de sus bytes, no por la extensión ni
 * por la metadata de un proveedor de documentos.
 */
internal fun detectPhotoFormat(header: ByteArray): PhotoFormat? = when {
    header.size >= 3 && header[0].unsigned() == 0xff &&
        header[1].unsigned() == 0xd8 && header[2].unsigned() == 0xff -> PhotoFormat.JPEG
    header.size >= PNG_SIGNATURE.size && header.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) ->
        PhotoFormat.PNG
    header.size >= 12 && header.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
        header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE) -> PhotoFormat.WEBP
    else -> null
}

/** Lee únicamente los primeros bytes necesarios para [detectPhotoFormat]. */
internal fun detectPhotoFormat(input: InputStream): PhotoFormat? {
    val header = ByteArray(12)
    var offset = 0
    while (offset < header.size) {
        val read = input.read(header, offset, header.size - offset)
        if (read == -1) break
        if (read == 0) continue
        offset += read
    }
    return detectPhotoFormat(header.copyOf(offset))
}

/** Resultado de una copia limitada que no conserva la foto completa en memoria. */
internal sealed interface PhotoCopyResult {
    data class Copied(val bytes: Long) : PhotoCopyResult

    data object ExceedsLimit : PhotoCopyResult
}

/**
 * Copia [input] por bloques a [output]. Si supera el límite, deja de leer y
 * comunica el rechazo para que el llamador borre el temporal parcial.
 */
internal fun copyPhotoStream(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_PHOTO_BYTES,
): PhotoCopyResult {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) return PhotoCopyResult.Copied(total)
        if (read == 0) continue
        total += read
        if (total > maxBytes) return PhotoCopyResult.ExceedsLimit
        output.write(buffer, 0, read)
    }
}

/**
 * Normaliza metadata opcional de proveedores. Nunca determina el tipo real:
 * ese se deriva siempre de [detectPhotoFormat].
 */
internal fun normalizePhotoMimeType(mimeType: String?): String? = when (mimeType?.trim()?.lowercase(Locale.ROOT)) {
    "image/jpg" -> "image/jpeg"
    "image/x-png" -> "image/png"
    "image/jpeg", "image/png", "image/webp" -> mimeType.trim().lowercase(Locale.ROOT)
    else -> null
}

private fun Byte.unsigned(): Int = toInt() and 0xff

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
)
private val RIFF_SIGNATURE = "RIFF".encodeToByteArray()
private val WEBP_SIGNATURE = "WEBP".encodeToByteArray()

/** Inspect container chunks, not compressed pixel bytes (which can coincidentally spell ANIM). */
internal fun isAnimatedPhoto(file: java.io.File): Boolean = file.inputStream().buffered().use { input ->
    val header = input.readPhotoBytes(12)
    val format = detectPhotoFormat(header)
    if (format != PhotoFormat.PNG && format != PhotoFormat.WEBP) return@use false
    if (format == PhotoFormat.PNG) {
        // PNG has an eight-byte signature: reopen at its first chunk.
        return@use file.inputStream().buffered().use pngChunks@ { png ->
            png.skipPhotoBytes(8)
            while (true) {
                val chunk = png.readPhotoBytes(8)
                if (chunk.size != 8) break
                val length = java.nio.ByteBuffer.wrap(chunk, 0, 4).int.toLong() and 0xffffffffL
                val type = String(chunk, 4, 4, Charsets.US_ASCII)
                if (type == "acTL") return@pngChunks true
                if (type == "IEND") break
                png.skipPhotoBytes(length + 4)
            }
            false
        }
    }
    while (true) {
        val chunk = input.readPhotoBytes(8)
        if (chunk.size != 8) break
        val type = String(chunk, 0, 4, Charsets.US_ASCII)
        val length = java.nio.ByteBuffer.wrap(chunk, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        if (type == "ANIM" || type == "ANMF") return@use true
        input.skipPhotoBytes(length + length % 2)
    }
    false
}

private fun InputStream.readPhotoBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(bytes, offset, size - offset)
        if (count < 0) break
        if (count == 0) continue
        offset += count
    }
    return bytes.copyOf(offset)
}
private fun InputStream.skipPhotoBytes(length: Long) {
    var remaining = length
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else if (read() >= 0) remaining--
        else throw java.io.EOFException("Incomplete image container")
    }
}
