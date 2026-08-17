package com.moonblogger.app.data.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoValidationTest {
    @Test
    fun `unknown-size stream at limit is accepted with its exact size`() {
        val bytes = ByteArray(MAX_PHOTO_BYTES.toInt())

        val result = countPhotoStreamBytes(ByteArrayInputStream(bytes))

        assertEquals(PhotoStreamSize.Exact(MAX_PHOTO_BYTES), result)
    }

    @Test
    fun `unknown-size stream over limit is rejected`() {
        val bytes = ByteArray(MAX_PHOTO_BYTES.toInt() + 1)

        val result = countPhotoStreamBytes(ByteArrayInputStream(bytes))

        assertEquals(PhotoStreamSize.ExceedsLimit, result)
    }

    @Test
    fun `normalizes provider MIME aliases but rejects unsupported metadata`() {
        assertEquals("image/jpeg", normalizePhotoMimeType("image/jpg"))
        assertEquals("image/jpeg", normalizePhotoMimeType("IMAGE/JPEG"))
        assertEquals("image/png", normalizePhotoMimeType(" image/x-png "))
        assertEquals(null, normalizePhotoMimeType("image/gif"))
        assertEquals(null, normalizePhotoMimeType(".jpg"))
    }

    @Test
    fun `jpeg signature determines real MIME despite untrusted provider metadata`() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)

        assertEquals(null, normalizePhotoMimeType("image/gif"))
        assertEquals(PhotoFormat.JPEG, detectPhotoFormat(jpeg))
        assertEquals("image/jpeg", detectPhotoFormat(jpeg)?.mimeType)
    }

    @Test
    fun `recognizes PNG signature`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        assertEquals(PhotoFormat.PNG, detectPhotoFormat(png))
    }

    @Test
    fun `recognizes WebP RIFF signature`() {
        val webp = "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".encodeToByteArray()

        assertEquals(PhotoFormat.WEBP, detectPhotoFormat(webp))
    }

    @Test
    fun `rejects GIF HEIC SVG and arbitrary bytes regardless of file naming metadata`() {
        val invalidHeaders = listOf(
            "GIF89a".encodeToByteArray(),
            byteArrayOf(0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63),
            "<svg".encodeToByteArray(),
            byteArrayOf(1, 2, 3, 4),
        )

        invalidHeaders.forEach { header -> assertEquals(null, detectPhotoFormat(header)) }
    }

    @Test
    fun `copies stream in blocks and removes discarded temporary copy`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val file = File.createTempFile("moonblogger-photo-test-", ".tmp")
        try {
            val result = file.outputStream().use { output ->
                copyPhotoStream(ByteArrayInputStream(source), output, maxBytes = source.size.toLong())
            }

            assertEquals(PhotoCopyResult.Copied(source.size.toLong()), result)
            assertTrue(file.readBytes().contentEquals(source))
        } finally {
            assertTrue(file.delete() || !file.exists())
            assertFalse(file.exists())
        }
    }

    @Test
    fun `copy stops at configured stream limit without retaining exceeding chunk`() {
        val output = ByteArrayOutputStream()

        val result = copyPhotoStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, maxBytes = 3)

        assertEquals(PhotoCopyResult.ExceedsLimit, result)
        assertEquals(0, output.size())
    }
}
