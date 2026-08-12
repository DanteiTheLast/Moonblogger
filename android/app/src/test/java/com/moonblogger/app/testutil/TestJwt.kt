package com.moonblogger.app.testutil

import java.util.Base64

/** Construye JWTs de prueba con el claim [exp] indicado (segundos). */
object TestJwt {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun withExp(exp: Long): String {
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"exp":$exp,"user_id":1,"username":"moon"}"""
        return encoder.encodeToString(header.toByteArray()) +
            "." +
            encoder.encodeToString(payload.toByteArray()) +
            ".signature"
    }

    fun notAJwt(): String = "not-a-jwt"
}
