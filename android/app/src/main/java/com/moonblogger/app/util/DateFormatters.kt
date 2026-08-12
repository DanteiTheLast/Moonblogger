package com.moonblogger.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formateo de fechas ISO 8601 (UTC) en español, equivalente al `formatDate`
 * de la web (p. ej. "12 de agosto de 2026").
 */
object DateFormatters {

    private val longDate: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale("es"))

    private val longDateTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", Locale("es"))

    private val localZone: ZoneId = ZoneId.systemDefault()

    /** "12 de agosto de 2026". Devuelve el texto original si no es parseable. */
    fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            longDate.format(Instant.parse(iso).atZone(localZone))
        } catch (_: Exception) {
            iso
        }
    }

    /** "12 de agosto de 2026, 14:30". Devuelve el texto original si no es parseable. */
    fun formatDateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            longDateTime.format(Instant.parse(iso).atZone(localZone))
        } catch (_: Exception) {
            iso
        }
    }
}
