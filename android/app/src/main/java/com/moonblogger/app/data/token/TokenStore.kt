package com.moonblogger.app.data.token

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Contrato de almacenamiento de tokens. Se define como interfaz para poder
 * sustituirlo por un fake en los tests JVM.
 */
interface TokenStore {

    var accessToken: String?
    var refreshToken: String?

    fun saveTokens(access: String, refresh: String)

    fun clear()

    fun hasTokens(): Boolean
}

/**
 * Implementación cifrada con [EncryptedSharedPreferences] (androidx.security:
 * security-crypto 1.1.0).
 *
 * DEUDA TÉCNICA ACEPTADA: `EncryptedSharedPreferences` está DEPRECADA upstream
 * (desde 1.1.0 se recomienda usar `SharedPreferences` + Android Keystore
 * directamente). Se mantiene en v1 porque ya está aceptada en la decisión D7
 * y funciona correctamente; la migración queda documentada en
 * `android/README.md` y `docs/decisions.md` (D7).
 */
@Suppress("DEPRECATION")
class EncryptedTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREF_FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS, value).apply()
        }

    override var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) {
            prefs.edit().putString(KEY_REFRESH, value).apply()
        }

    override fun saveTokens(access: String, refresh: String) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun hasTokens(): Boolean = accessToken != null && refreshToken != null

    private companion object {
        const val PREF_FILE_NAME = "moonblogger_secure_prefs"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
    }
}
