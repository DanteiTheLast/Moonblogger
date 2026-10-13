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
    Los 401 de login/refresh no se reintentan. Sólo un refresh rechazado
    con 401 invalida los tokens; timeouts, 429 y 5xx conservan la sesión
    para volver a intentar. Un refresh en vuelo no puede restaurar tokens
    después de cerrar sesión.
  - `SessionManager.initialize()` refresca el access al arrancar si `exp`
    (JWT) está caducado.
  - Tokens en `EncryptedSharedPreferences` (`security-crypto` 1.1.0).

## Configuración

- `local.properties` (NO versionado) o `gradle.properties`:
  - `moonblogger.apiBaseUrlDebug` → por defecto `http://10.0.2.2:8000/`
    (emulador → host). Dispositivo físico: la IP del equipo.
  - `moonblogger.apiBaseUrlRelease` → URL real de la API en Render Free,
    definida en `local.properties` (obligatorio con barra final, p. ej.
    `https://<nombre>.onrender.com/`).
    Nota: el servicio de Render Free se duerme tras ~15 min sin tráfico; el
    primer request puede tardar 30-60 s (cold start). Los timeouts de OkHttp en
    `AppContainer.kt` lo toleran (connect 30 s / read 90 s).
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
refresh single-flight, fallos transitorios y logout concurrente,
flujo 401→refresh→reintento, CRUD, paginación y recuperación del editor.

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
- **Paginación:** la lista usa `page` y muestra «Cargar más publicaciones»
  mientras la respuesta incluya `next`; un error conserva las entradas cargadas.
- **`runBlocking` en `TokenAuthenticator`:** OkHttp 5 no ofrece Authenticator
  suspendible. Se usa `runBlocking` breve; el single-flight limita a UNA
  llamada de refresh concurrente. Aceptable para una app de un solo usuario.
- **Sin endpoint de logout:** el cierre de sesión es solo local (borrar
  tokens). El refresh queda invalidado en el servidor por rotación/expiración.
- **FOTOS / futura etapa vídeo:** las cargas siguen en primer plano. La copia
  local conserva IDs de intents antes de transferir bytes y IDs de fotos ya
  confirmadas. Las fotos que todavía no terminaron de subir y sus archivos
  temporales no se recuperan tras cerrar el editor o morir el proceso: la UI
  lo advierte y exige revisar las fotos antes de habilitar Guardar. No existe
  una cola de subidas en segundo plano; esa mejora queda pendiente.
- **Autoguardado y rendimiento:** la copia local se escribe de forma atómica
  con `fsync` por cambio. Hay que evaluar latencia al escribir textos largos
  en el teléfono real; todavía no se ha probado la fluidez con ese hardware.

## Recuperación del editor

Texto, título, estado elegido, transición, orden y metadatos de fotos confirmadas,
ID del post de trabajo y borrados pendientes se conservan en JSON privado bajo
`noBackupFilesDir`. El ámbito incluye la identidad `user_id`, la URL de API y el
post original (o «nuevo»), de modo que otra cuenta no restaura ni borra la copia.
No se guardan tokens, URLs firmadas ni permisos temporales de galería. La copia
se elimina después de un guardado completo o mediante descarte explícito.

La navegación hacia atrás permite conservar el escrito o descartar la copia
local; descartar no revierte pasos ya completados en el servidor. Si falla el
almacenamiento local, la UI advierte y deshabilita «Conservar y salir». Una copia
ilegible exige descarte explícito antes de editar. Un fallo de carga inicial de
la publicación bloquea el formulario y ofrece reintentar, evitando sobrescribir
el original con un formulario vacío.

Los guardados parciales conservan el ID creado para reintentar mediante PATCH,
retiran de la cola cada DELETE confirmado y tratan 404 como borrado ya completado.
Los borrados se ejecutan antes de subir sustituciones al límite de diez fotos.
Preparar fotos de un post existente omite `status`; sólo el PATCH final aplica
el estado elegido. La app informa de cambios parciales, sin afirmar que se
revirtieron o que el servidor quedó en borrador.

Un POST cuyo servidor creó un post pero cuya respuesta nunca llegó todavía puede
crear un duplicado al reintentar: eliminar esa incertidumbre necesita una clave
de idempotencia compartida con la API. La recuperación del editor con datos y
fotos reales, TalkBack y restauración tras matar el proceso requiere verificación
en dispositivo; los tests JVM cubren la lógica y los archivos privados aislados.

## Preparación de imágenes

El editor acepta JPEG, PNG y WebP estáticos de hasta 32 MiB como entrada. Antes de subir, procesa una copia privada a un máximo de 2560 px en el lado mayor, sin ampliar imágenes pequeñas. Usa JPEG de calidad 88 para imágenes opacas y PNG sin pérdidas cuando hay transparencia real. La re-codificación elimina los metadatos, incluida la ubicación, y aplica las ocho orientaciones EXIF. No modifica el archivo original del teléfono.

La API sigue recibiendo un máximo de 8 MiB por imagen: si una salida PNG con transparencia supera ese límite, se rechaza con un mensaje visible; no se aplana ni se sube el original. Tampoco se convierten animaciones a una primera imagen. Los bytes y dimensiones del intento de subida corresponden al archivo final optimizado.

Las pruebas `PhotoOptimizerTest` usan gráficos nativos de Robolectric para ejercitar los codecs Android, orientación, transparencia y limpieza de temporales. No sustituyen la comprobación de rendimiento y memoria en teléfonos reales.
