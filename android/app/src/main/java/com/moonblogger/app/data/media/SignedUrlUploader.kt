package com.moonblogger.app.data.media

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Cliente de transporte para Supabase Storage. Recibe un OkHttp dedicado sin
 * interceptor de autenticación, TokenAuthenticator ni logging: una signed URL
 * no debe llevar el JWT de Django ni quedar expuesta en logs.
 */
class SignedUrlUploader(private val client: OkHttpClient) {
    fun put(uploadUrl: String, file: UploadFile) {
        val body = object : RequestBody() {
            override fun contentType() = file.mimeType.toMediaType()

            override fun contentLength() = file.sizeBytes

            override fun writeTo(sink: BufferedSink) {
                file.openStream().use { input -> sink.writeAll(input.source()) }
            }
        }
        val request = Request.Builder()
            .url(uploadUrl)
            .header("Content-Type", file.mimeType)
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("La carga de la imagen falló (código ${response.code}).")
            }
        }
    }
}
