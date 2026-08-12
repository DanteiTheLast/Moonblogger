package com.moonblogger.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
