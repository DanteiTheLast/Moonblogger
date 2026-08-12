package com.moonblogger.app.testutil

import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf

/** Respuesta JSON típica del backend (DRF) para el MockWebServer. */
fun jsonResponse(code: Int, body: String): MockResponse =
    MockResponse(
        code = code,
        headers = headersOf("Content-Type", "application/json"),
        body = body,
    )

/** Post de ejemplo tal y como lo devuelve el serializador del backend. */
const val SAMPLE_POST_JSON = """
    {
      "id": 1,
      "slug": "mi-primer-post",
      "title": "Mi primer post",
      "content": "Hola mundo",
      "status": "draft",
      "created_at": "2026-08-11T12:00:00Z",
      "updated_at": "2026-08-11T12:30:00Z",
      "published_at": null
    }
"""
