package com.moonblogger.app.data.remote

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException

/**
 * Utilidades para transformar errores de la API (formato DRF) en mensajes
 * legibles para la UI:
 *  - 400 → {"campo": ["mensaje", ...]}
 *  - 401/403/404 → {"detail": "..."}
 */
object ApiErrors {

    private val json = Json { ignoreUnknownKeys = true }

    /** Extrae un mensaje legible del cuerpo de error DRF, o null si no se puede. */
    fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val element = json.parseToJsonElement(body)
            if (element !is JsonObject) return null

            element["detail"]?.let { detail ->
                val text = (detail as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrBlank()) return text
            }

            val fieldErrors = element.entries.flatMap { (field, value) ->
                when (value) {
                    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .filter { it.isNotBlank() }
                        .map { "$field: $it" }

                    else -> emptyList()
                }
            }
            if (fieldErrors.isNotEmpty()) fieldErrors.joinToString("\n") else null
        } catch (_: Exception) {
            null
        }
    }

    /** Convierte una excepción de la capa de red en un mensaje para el usuario. */
    fun userMessage(throwable: Throwable): String {
        throwable.findHttpException()?.let { error ->
            val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            return parseErrorMessage(body) ?: "Error del servidor (código ${error.code()})."
        }
        return when {
            throwable.hasCause<IOException>() ->
                "No se pudo conectar con el servidor. Comprueba tu conexión e inténtalo de nuevo."

            else -> throwable.message ?: "Ha ocurrido un error inesperado."
        }
    }

    /** Busca primero los errores HTTP anidados por flujos que conservan contexto adicional. */
    private fun Throwable.findHttpException(): HttpException? =
        causeSequence().filterIsInstance<HttpException>().firstOrNull()

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        causeSequence().any { it is T }

    private fun Throwable.causeSequence(): Sequence<Throwable> = sequence {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = this@causeSequence
        while (current != null && seen.add(current)) {
            yield(current)
            current = current.cause
        }
    }
}

/** Ejecuta una llamada de red devolviendo Result; las cancelaciones se propagan. */
suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
