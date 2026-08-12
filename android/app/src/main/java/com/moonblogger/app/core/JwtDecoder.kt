package com.moonblogger.app.core

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Decodificador mínimo de JWT para leer la reclamación `exp` (expiración).
 *
 * Solo se usa para decidir si el access token está caducado al arrancar la app
 * (gestión de sesión), no para validar firmas: la validación real la hace el
 * backend. Si el token es ilegible o no tiene `exp`, se trata como "no
 * caducado" y se deja que el servidor decida (el Authenticator refrescará si
 * responde 401).
 */
object JwtDecoder {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param token token JWT (access).
     * @param nowEpochSeconds instante de comparación; inyectable para tests.
     * @return true si el token tiene `exp` y `exp <= now`.
     */
    fun isExpired(token: String, nowEpochSeconds: Long = Instant.now().epochSecond): Boolean {
        val exp = expClaimSeconds(token) ?: return false
        return exp <= nowEpochSeconds
    }

    /** Devuelve el claim `exp` en segundos, o null si no se puede leer. */
    fun expClaimSeconds(token: String): Long? {
        val parts = token.split('.')
        if (parts.size < 3) return null
        val payload = try {
            Base64.getUrlDecoder().decode(padBase64(parts[1]))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val root = try {
            json.parseToJsonElement(payload.decodeToString()).jsonObject
        } catch (_: Exception) {
            return null
        }
        return (root["exp"] as? JsonPrimitive)?.longOrNull
    }

    private fun padBase64(value: String): String {
        val rem = value.length % 4
        return if (rem == 0) value else value + "=".repeat(4 - rem)
    }
}
