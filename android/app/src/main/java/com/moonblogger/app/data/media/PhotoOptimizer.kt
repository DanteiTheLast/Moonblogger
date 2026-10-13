package com.moonblogger.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal class PhotoOptimizationException(val reason: PhotoRejectionReason) : Exception()

/** Android codecs re-encode pixels only: location/EXIF from the source never reach the upload. */
internal object PhotoOptimizer {
    fun optimize(source: File, destination: File): InspectedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PHOTO_PIXELS) {
            throw PhotoOptimizationException(PhotoRejectionReason.INVALID_IMAGE)
        }
        if (isAnimatedPhoto(source)) throw PhotoOptimizationException(PhotoRejectionReason.UNSUPPORTED_IMAGE_FORMAT)
        val exif = ExifInterface(source)
        var sample = 1
        // A decoded image is at most about 2*2560 on its longest side; never decode the full original.
        while (max(width, height) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
        while (((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > 12_000_000L) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }) ?: throw PhotoOptimizationException(PhotoRejectionReason.INVALID_IMAGE)
        val allocated = mutableListOf(decoded)
        try {
            val longest = max(decoded.width, decoded.height)
            val scale = minOf(1.0, MAX_PHOTO_SIDE.toDouble() / longest)
            val resized = Bitmap.createScaledBitmap(decoded,
                max(1, (decoded.width * scale).roundToInt()),
                max(1, (decoded.height * scale).roundToInt()), true)
            if (resized !== decoded) {
                allocated += resized
                decoded.recycle()
                allocated.remove(decoded)
            }
            val transform = Matrix().apply {
                // AndroidX understands all eight EXIF cases, including transpose/transverse mirrors.
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            val oriented = Bitmap.createBitmap(resized, 0, 0, resized.width, resized.height, transform, true)
            if (oriented !== resized) {
                allocated += oriented
                resized.recycle()
                allocated.remove(resized)
            }
            val transparent = hasTransparentPixels(oriented)
            val codec = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            destination.outputStream().buffered().use { stream ->
                val bounded = BoundedPhotoOutput(stream)
                if (!oriented.compress(codec, 88, bounded)) {
                    throw PhotoOptimizationException(PhotoRejectionReason.OPTIMIZATION_FAILED)
                }
            }
            if (destination.length() == 0L || destination.length() > MAX_PHOTO_BYTES) {
                throw PhotoOptimizationException(PhotoRejectionReason.OPTIMIZATION_FAILED)
            }
            return InspectedPhoto(destination, if (transparent) "image/png" else "image/jpeg",
                destination.length(), oriented.width, oriented.height)
        } finally {
            allocated.reversed().forEach { it.recycle() }
        }
    }

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr 24) != 255 }) return true
        }
        return false
    }
}

/** Stop encoding at the API's actual limit; a partial file is deleted by the owner. */
private class BoundedPhotoOutput(output: OutputStream) : FilterOutputStream(output) {
    private var count = 0L
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (count + length > MAX_PHOTO_BYTES) throw PhotoOptimizationException(PhotoRejectionReason.OPTIMIZATION_FAILED)
        out.write(bytes, offset, length)
        count += length
    }
    override fun write(value: Int) {
        if (count + 1 > MAX_PHOTO_BYTES) throw PhotoOptimizationException(PhotoRejectionReason.OPTIMIZATION_FAILED)
        out.write(value)
        count++
    }
}
