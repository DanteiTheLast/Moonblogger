package com.moonblogger.app.data.remote

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorsTest {

    @Test
    fun `parses DRF field errors from 400`() {
        val body = """{"title": ["El título no puede estar vacío."]}"""
        assertEquals("title: El título no puede estar vacío.", ApiErrors.parseErrorMessage(body))
    }

    @Test
    fun `parses detail from 401`() {
        val body = """{"detail": "No active account found with the given credentials"}"""
        assertEquals(
            "No active account found with the given credentials",
            ApiErrors.parseErrorMessage(body),
        )
    }

    @Test
    fun `returns null for non-error JSON`() {
        assertNull(ApiErrors.parseErrorMessage("""{"count": 0}"""))
        assertNull(ApiErrors.parseErrorMessage(""))
    }

    @Test
    fun `returns null for invalid JSON`() {
        assertNull(ApiErrors.parseErrorMessage("not-json"))
    }

    @Test
    fun `uses DRF detail from nested HTTP exception before IO classification`() {
        val httpError = HttpException(
            Response.error<Any>(
                400,
                """{"detail":"La imagen no coincide con el intent."}"""
                    .toResponseBody("application/json".toMediaType()),
            ),
        )

        assertEquals(
            "La imagen no coincide con el intent.",
            ApiErrors.userMessage(IOException("wrapper", httpError)),
        )
    }
}
