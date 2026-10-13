package com.moonblogger.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoOptimizerTest {
    private fun withFiles(block: (File, File) -> Unit) {
        val source = File.createTempFile("moon-original-", ".img")
        val output = File.createTempFile("moon-optimized-", ".img")
        try { block(source, output) } finally { source.delete(); output.delete() }
    }
    private fun save(bitmap: Bitmap, source: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG) {
        source.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
        bitmap.recycle()
    }

    @Test fun `native codec reduces a larger than 15 MiB input with bounded dimensions and matching metadata`() = withFiles { source, output ->
        val bitmap = Bitmap.createBitmap(4000, 2000, Bitmap.Config.ARGB_8888)
        val random = Random(83)
        val row = IntArray(4000)
        for (y in 0 until 2000) {
            for (x in row.indices) row[x] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            bitmap.setPixels(row, 0, 4000, 0, y, 4000, 1)
        }
        save(bitmap, source)
        val originalBytes = source.length()
        assertTrue("Fixture must genuinely exceed 15 MiB: $originalBytes", originalBytes > 15 * 1024 * 1024)
        assertTrue(originalBytes <= MAX_PHOTO_INPUT_BYTES)
        val result = PhotoOptimizer.optimize(source, output)
        val cache = java.nio.file.Files.createTempDirectory("moon-large-source-").toFile()
        try {
            val provider = PhotoSourceProvider(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, cache)
            val selection = provider.inspect(listOf(android.net.Uri.fromFile(source)))
            assertTrue(selection.rejections.isEmpty())
            assertEquals(result.width, selection.photos.single().width)
            assertEquals(result.height, selection.photos.single().height)
            provider.release(selection.photos.single())
        } finally { cache.deleteRecursively() }
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        try {
            assertEquals(2560, result.width)
            assertEquals(1280, result.height)
            assertEquals(result.width, decoded.width)
            assertEquals(result.height, decoded.height)
            assertEquals("image/jpeg", result.mimeType)
            assertEquals(output.length(), result.sizeBytes)
            assertTrue(result.sizeBytes < MAX_PHOTO_BYTES)
            assertTrue(result.sizeBytes < originalBytes / 3)
            assertEquals(originalBytes, source.length())
            println("PHOTO_CODEC_BENCHMARK input=$originalBytes output=${result.sizeBytes} size=${result.width}x${result.height}")
        } finally { decoded.recycle() }
    }

    @Test fun `transparency survives PNG encoding and small assets do not upscale`() = withFiles { source, output ->
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(90, 255, 0, 0))
        bitmap.setPixel(1, 1, Color.TRANSPARENT)
        save(bitmap, source)
        val result = PhotoOptimizer.optimize(source, output)
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        try {
            assertEquals("image/png", result.mimeType)
            assertEquals(40, result.width)
            assertEquals(20, result.height)
            assertEquals(0, Color.alpha(decoded.getPixel(1, 1)))
            assertEquals(90, Color.alpha(decoded.getPixel(10, 10)))
        } finally { decoded.recycle() }
    }

    @Test fun `all EXIF orientations mirror and rotate pixels and remove GPS metadata`() {
        val expectedCorners = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
        )
        for (orientation in 1..8) withFiles { source, output ->
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            for (y in 0 until 40) for (x in 0 until 80) {
                bitmap.setPixel(x, y, expectedCorners[0][(if (y >= 20) 2 else 0) + (if (x >= 40) 1 else 0)])
            }
            save(bitmap, source, Bitmap.CompressFormat.JPEG)
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setLatLong(19.4, -99.1)
                saveAttributes()
            }
            val result = PhotoOptimizer.optimize(source, output)
            val decoded = BitmapFactory.decodeFile(output.absolutePath)
            try {
                assertEquals(if (orientation >= 5) 40 else 80, result.width)
                assertEquals(if (orientation >= 5) 80 else 40, result.height)
                val corners = listOf(decoded.getPixel(5, 5), decoded.getPixel(decoded.width - 6, 5),
                    decoded.getPixel(5, decoded.height - 6), decoded.getPixel(decoded.width - 6, decoded.height - 6))
                corners.zip(expectedCorners[orientation - 1]).forEach { (actual, expected) ->
                    assertTrue("orientation=$orientation color=$actual expected=$expected",
                        kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 15 &&
                        kotlin.math.abs(Color.green(actual) - Color.green(expected)) < 15 &&
                        kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) < 15)
                }
                assertNull(ExifInterface(output).latLong)
            } finally { decoded.recycle() }
        }
    }

    @Test fun `animation detection inspects real container chunk names`() = withFiles { source, _ ->
        source.writeBytes("RIFF".toByteArray() + byteArrayOf(20, 0, 0, 0) + "WEBP".toByteArray() +
            "ANIM".toByteArray() + ByteArray(4))
        assertTrue(isAnimatedPhoto(source))
        source.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) +
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(8).array() + "acTL".toByteArray())
        assertTrue(isAnimatedPhoto(source))
    }

    @Test fun `provider removes source copy and release removes optimized file only`() = withFiles { source, _ ->
        val bitmap = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        save(bitmap, source)
        val bytes = source.readBytes()
        val cache = java.nio.file.Files.createTempDirectory("moon-provider-").toFile()
        try {
            val provider = PhotoSourceProvider(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, cache)
            val selection = provider.inspect(listOf(android.net.Uri.fromFile(source)))
            assertEquals(emptyList<PhotoRejection>(), selection.rejections)
            val photo = selection.photos.single()
            assertEquals(1, File(cache, "moonblogger-photos").listFiles()!!.size)
            assertEquals(photo.sizeBytes, provider.uploadFile(photo).sizeBytes)
            assertArrayEquals(bytes, source.readBytes())
            provider.release(photo)
            assertEquals(0, File(cache, "moonblogger-photos").listFiles()!!.size)
            assertTrue(source.exists())
        } finally { cache.deleteRecursively() }
    }

    @Test fun `oversized lossless alpha is rejected and both temporary files deleted`() = withFiles { source, _ ->
        val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val random = Random(47)
        val row = IntArray(2048)
        for (y in 0 until 2048) {
            for (x in row.indices) row[x] = Color.argb(128 + random.nextInt(127), random.nextInt(256), random.nextInt(256), random.nextInt(256))
            bitmap.setPixels(row, 0, 2048, 0, y, 2048, 1)
        }
        save(bitmap, source)
        assertTrue(source.length() > MAX_PHOTO_BYTES)
        assertTrue(source.length() < MAX_PHOTO_INPUT_BYTES)
        val cache = java.nio.file.Files.createTempDirectory("moon-provider-").toFile()
        try {
            val provider = PhotoSourceProvider(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, cache)
            val selection = provider.inspect(listOf(android.net.Uri.fromFile(source)))
            assertTrue(selection.photos.isEmpty())
            assertEquals(PhotoRejectionReason.OPTIMIZATION_FAILED, selection.rejections.single().reason)
            assertEquals(0, File(cache, "moonblogger-photos").listFiles()!!.size)
            assertTrue(source.exists())
        } finally { cache.deleteRecursively() }
    }

    @Test fun `provider rejects source over 32 MiB and cleans partial copy`() = withFiles { source, _ ->
        java.io.RandomAccessFile(source, "rw").use { it.setLength(MAX_PHOTO_INPUT_BYTES + 1) }
        val cache = java.nio.file.Files.createTempDirectory("moon-provider-limit-").toFile()
        try {
            val provider = PhotoSourceProvider(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, cache)
            val selection = provider.inspect(listOf(android.net.Uri.fromFile(source)))
            assertTrue(selection.photos.isEmpty())
            assertEquals(PhotoRejectionReason.FILE_TOO_LARGE, selection.rejections.single().reason)
            assertEquals(0, File(cache, "moonblogger-photos").listFiles()!!.size)
        } finally { cache.deleteRecursively() }
    }

    @Test fun `invalid image cannot be substituted with its original`() = withFiles { source, output ->
        source.writeText("not a photo")
        try { PhotoOptimizer.optimize(source, output); fail("Must reject") }
        catch (error: PhotoOptimizationException) { assertEquals(PhotoRejectionReason.INVALID_IMAGE, error.reason) }
        assertEquals(0L, output.length())
    }
}
