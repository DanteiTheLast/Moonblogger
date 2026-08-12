package com.moonblogger.app.core

import com.moonblogger.app.testutil.TestJwt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JwtDecoderTest {

    @Test
    fun `expired token is expired`() {
        val token = TestJwt.withExp(1_700_000_000L)
        assertTrue(JwtDecoder.isExpired(token, nowEpochSeconds = 1_700_000_001L))
    }

    @Test
    fun `valid token is not expired`() {
        val token = TestJwt.withExp(1_900_000_000L)
        assertFalse(JwtDecoder.isExpired(token, nowEpochSeconds = 1_700_000_000L))
    }

    @Test
    fun `exp equal to now is expired`() {
        val token = TestJwt.withExp(1_700_000_000L)
        assertTrue(JwtDecoder.isExpired(token, nowEpochSeconds = 1_700_000_000L))
    }

    @Test
    fun `malformed token is treated as not expired`() {
        assertFalse(JwtDecoder.isExpired(TestJwt.notAJwt(), nowEpochSeconds = 1_700_000_001L))
        assertFalse(JwtDecoder.isExpired("", nowEpochSeconds = 1_700_000_001L))
    }

    @Test
    fun `expClaimSeconds returns null for invalid payload`() {
        assertNull(JwtDecoder.expClaimSeconds("aaa.bbb.ccc"))
    }

    @Test
    fun `expClaimSeconds parses exp`() {
        assertEquals(1_700_000_000L, JwtDecoder.expClaimSeconds(TestJwt.withExp(1_700_000_000L)))
    }
}
