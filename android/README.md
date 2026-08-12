# MoonBlogger — Android

Cliente Android de MoonBlogger. Kotlin + Jetpack Compose (Material 3).

## Arquitectura

```
Android → Django REST API (Retrofit/OkHttp) → PostgreSQL
```

- **UI:** Compose Material 3 + Navigation Compose. Tema pastel replicado de
  `web/app/tokens.css`.
- **Estado:** ViewModel + StateFlow (sin Hilt; DI manual, decisión D7).
- **Red:** Retrofit + OkHttp + kotlinx.serialization.
- **Sesión:** JWT (SimpleJWT, D1). Access 15 min, refresh 7 días **con
  rotación**: cada respuesta de `/auth/refresh/` trae un refresh nuevo que la
  app persiste.
  - `AuthInterceptor` añade `Authorization: Bearer`.
  - `TokenAuthenticator` ante 401: refresh single-flight (`AuthRefresher`) y
    reintento único con header `X-MoonBlogger-Auth-Retry` (anti-bucle).
    Los 401 de login/refresh no se reintentan; si el refresh falla, logout
    local (no existe endpoint de logout).
  - `SessionManager.initialize()` refresca el access al arrancar si `exp`
    (JWT) está caducado.
  - Tokens en `EncryptedSharedPreferences` (`security-crypto` 1.1.0).

## Configuración

- `local.properties` (NO versionado) o `gradle.properties`:
  - `moonblogger.apiBaseUrlDebug` → por defecto `http://10.0.2.2:8000/`
    (emulador → host). Dispositivo físico: la IP del equipo.
  - `moonblogger.apiBaseUrlRelease` → dominio real (placeholder).
- `src/debug/res/xml/network_security_config.xml` permite cleartext en debug;
  release solo HTTPS.

## Compilar y probar

```bash
./gradlew :app:compileDebugKotlin       # compilar
./gradlew :app:testDebugUnitTest        # tests JVM (mockwebserver3)
./gradlew :app:connectedDebugAndroidTest # tests instrumentados (emulador)
```

Los tests JVM no requieren Android: cubren JwtDecoder, parseo de errores DRF,
refresh single-flight, flujo 401→refresh→reintento y CRUD del PostRepository.

## Deuda técnica aceptada (v1)

- **D7 / tokens:** `EncryptedSharedPreferences` está deprecada upstream
  (security-crypto 1.1.0). Funciona, pero la migración (SharedPreferences +
  Android Keystore) está pendiente.
- **Tipografía:** la web usa Nunito (texto) y Baloo 2 (display); en Android se
  usa la tipografía M3 por defecto. Integración de fuentes pendiente (cambio
  aditivo, no bloquea funcionalidad).
- **Paginación:** la lista solo consume la primera página (page_size 20).
  Gestión de `next`/carga infinita pendiente.
- **`runBlocking` en `TokenAuthenticator`:** OkHttp 5 no ofrece Authenticator
  suspendible. Se usa `runBlocking` breve; el single-flight limita a UNA
  llamada de refresh concurrente. Aceptable para una app de un solo usuario.
- **Sin endpoint de logout:** el cierre de sesión es solo local (borrar
  tokens). El refresh queda invalidado en el servidor por rotación/expiración.
