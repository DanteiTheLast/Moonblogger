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
  - `moonblogger.apiBaseUrlRelease` → placeholder `https://api.moonblogger.example/`;
    en el despliegue se define el subdominio real de Koyeb vía
    `local.properties` o `gradle.properties`.
- `keystore.properties` (NO versionado): credenciales de firma del release,
  generado por `scripts/create-keystore.sh` (ver sección "Build de release").
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

## Build de release (APK para instalación directa)

La app se distribuye como APK release firmado e instalado directamente en el
dispositivo de Moon (sin Play Store). Se necesita un keystore de firma propio.

```bash
# 1) Generar el keystore de firma (solo la primera vez; custódialo, ver nota)
./scripts/create-keystore.sh

# 2) Compilar el APK release firmado
./gradlew assembleRelease

# 3) El APK queda en:
#    app/build/outputs/apk/release/app-release.apk

# 4) Instalar en el dispositivo (depuración USB o adb por Wi-Fi)
adb install -r app/build/outputs/apk/release/app-release.apk
```

`build.gradle.kts` lee las credenciales de `android/keystore.properties`
(NO versionado). Si el archivo no existe, `assembleRelease` no rompe: produce
un APK sin firmar (`app-release-unsigned.apk`) que Android rechazará al
instalar; `assembleDebug`, tests y lint siguen funcionando sin él.

### Custodia del keystore

El keystore (por defecto `android/moonblogger-release.jks`) es la
**identidad de firma** de la app:

- Si se pierde, NO se puede instalar una actualización sobre la misma app:
  Android exige que las actualizaciones se firmen con la misma clave.
- La contraseña no se puede recuperar: no existe "reset".
- Guarda una copia offline (disco externo / gestor de contraseñas) junto con
  `storePassword` y `keyPassword`, y NUNCA lo versiones: `.gitignore` excluye
  `keystore.properties` y `*.jks`.

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
