# MoonBlogger — Despliegue y operación

Guía de despliegue a producción en planes gratuitos y de operación diaria.
Precios y límites son los de los planes free (Render Free, Supabase Free,
Vercel Hobby) en el momento de escribir este documento (17/08/2026).

## Arquitectura desplegada

| Capa | Plataforma | Qué se ejecuta | Coste |
|---|---|---|---|
| API | Render Free | Django + DRF (gunicorn + WhiteNoise), imagen desde `backend/Dockerfile`, Blueprint `render.yaml` | $0 |
| Base de datos | Supabase Free | PostgreSQL gestionado (session pooler) | $0 |
| Web | Vercel Hobby | Next.js **ISR** (`revalidate: 3600`, `tags: ['posts']`), invalidación por webhook | $0 |
| Android | Dispositivo de Moon | APK release firmado, instalado directamente | $0 |

## Antes de empezar (prerrequisitos)

- Cuentas creadas: **GitHub**, **Render**, **Supabase**, **Vercel**.
- Repositorio en GitHub con este proyecto (Render y Vercel despliegan desde él).
- La API de MoonBlogger se conecta a PostgreSQL vía ORM. **Ningún cliente**
  (Android/Web) accede a la base de datos directamente.

---

## 1. Base de datos: Supabase Free

1. Crear un proyecto en Supabase (región cercana; cualquier región sirve).
2. En **Project Settings → Database** anotar:
   - **Connection string** (modo *session pooler*, puerto 5432) o bien las
     partes por separado: host `aws-<region>.pooler.supabase.com`,
     usuario `postgres.<project-ref>`, puerto `5432`.
   - **Database password**: la que se fije al crear el proyecto (solo se
     muestra una vez).
3. Estas credenciales alimentan las variables `DB_*` del backend (paso 2).
   Con el pooler *session* el `sslmode` es `require`.

> Nota sobre pausa: Supabase Free **pausa el proyecto tras 7 días sin
> actividad** y lo reanuda con la siguiente petición (los datos no se pierden).
> Para un blog personal suele ser aceptable; ver [Ping anti-pausa](#ping-anti-pausa).

### Crear el esquema y el usuario de Moon

Ejecutar una vez contra la BD de producción (desde una máquina con
`backend/.venv` y el código del repo):

```bash
cd backend
.venv/bin/python manage.py migrate
MOON_USERNAME=moon MOON_PASSWORD=<password-real-de-moon> \
  .venv/bin/python manage.py create_moon_user
```

Las variables de entorno deben apuntar a Supabase (mismo juego `DB_*` del paso
de despliegue, con `DB_SSLMODE=require`). Usar una contraseña real para Moon,
distinta de la local.

---

## 2. API: Render Free

### Crear el servicio (Blueprint)

1. La configuración del servicio vive en **`render.yaml`** (raíz del repo):
   web service `moonblogger-api`, `runtime: docker`, `rootDir: backend`, plan
   `free`, `healthCheckPath: /api/v1/health/` y las variables de entorno
   (no-secretas).
2. En Render → **New + → Blueprint** → conectar GitHub → repositorio de
   MoonBlogger, rama `main`. Elegir **región** (recomendada: la más cercana a
   Supabase `ca-central-1`, p. ej. Ohio/Virginia si está disponible para Free).
   Render parsea el blueprint y crea el servicio.
3. URL pública resultante: `https://moonblogger-api.onrender.com`.

### Variables de entorno

El blueprint define las no-secretas y auto-genera la secret key:

| Variable | Origen | Valor |
|---|---|---|
| `DJANGO_DEBUG` | blueprint | `false` |
| `DJANGO_SECRET_KEY` | blueprint | auto-generada por Render (`generateValue`), nunca en el repo |
| `DJANGO_ALLOWED_HOSTS` | código | auto-resuelto en `settings.py` desde `RENDER_EXTERNAL_HOSTNAME`; usar solo para dominios adicionales (p. ej. un dominio propio futuro) |
| `DB_HOST` | dashboard | `aws-<region>.pooler.supabase.com` |
| `DB_USER` | dashboard | `postgres.<project-ref>` |
| `DB_PASSWORD` | dashboard | password de la BD de Supabase |
| `DB_NAME` | blueprint | `postgres` |
| `DB_PORT` | blueprint | `5432` |
| `DB_SSLMODE` | blueprint | `require` |
| `CORS_ALLOWED_ORIGINS` | dashboard | opcional; orígenes adicionales de la web |

- `DB_HOST`, `DB_USER` y `DB_PASSWORD` llevan `sync: false` en el blueprint: se
  definen **una vez** en el dashboard (Service → **Environment**) y Render
  redepliega solo. No se versionan (el repo es público).
- `PORT` lo inyecta Render (10000 por defecto); gunicorn escucha en
  `0.0.0.0:${PORT:-8000}` con `--workers ${WEB_CONCURRENCY:-1} --threads 2`
  (1 worker es lo sano para el plan free: 512 MB / 0.1 CPU).
- Con `DJANGO_DEBUG=false`, el arranque falla si falta `DJANGO_SECRET_KEY`
  (fail-fast); `DJANGO_ALLOWED_HOSTS` se completa automáticamente con el
  subdominio de Render.

5. Tras el despliegue, verificar: `GET /api/v1/health/`
   → `{"status": "ok"}` y `GET /api/v1/public/posts/` (toca la BD) responden.
   `/admin/` debe cargar.

> Los estáticos del admin los sirve WhiteNoise (`collectstatic` se ejecuta en
> el build de la imagen); no hace falta servidor de estáticos aparte.
>
> **Limpieza de media:** Render Free no requiere Shell ni cron para la limpieza
> habitual. Cada creación autenticada de un intent de carga procesa como máximo
> 10 intents `pending`/`failed` vencidos y 10 tareas persistentes de borrado.
> El límite evita añadir latencia no acotada a una petición; un backlog mayor
> avanza con los siguientes intents. `manage.py cleanup_media_storage --limit N`
> conserva el wrapper operativo para ejecuciones manuales donde estén
> disponibles. Si Storage no está disponible, las tareas se conservan para
> reintento; la creación de la URL de carga sigue requiriendo la configuración
> normal de Storage.
>
> **Migraciones:** se aplican manualmente desde una máquina local con el código
> (misma convención que la creación inicial; ver [Sección 1](#1-base-de-datos-supabase-free)).
> El `health/` no consulta la BD: un deploy puede quedar "healthy" con
> credenciales de Supabase incorrectas → siempre verificar `public/posts/`.

---

## 3. Web: Vercel Hobby (ISR)

1. En Vercel → **Add New → Project** → importar el repositorio de MoonBlogger.
2. **Root Directory**: `web` (el monorepo también contiene `backend/` y
   `android/`).
3. **Environment Variables**:
   - `API_BASE_URL` → `https://moonblogger-api.onrender.com/api/v1` (sin barra
     final).
   - `SITE_URL` → la URL pública de la web, p. ej.
     `https://<proyecto>.vercel.app` (sin barra final).
   - `REVALIDATE_SECRET` → **string aleatorio seguro** (mismo valor que
     `WEB_REVALIDATE_SECRET` en Render). Se usa para firmar/validar el webhook
     de revalidación (`X-Revalidate-Secret` = SHA-256 de este secreto).
4. **Build Command**: el por defecto de Next (`npm run build`).
5. Deploy. La web queda en `https://<proyecto>.vercel.app`.

### Revalidación de contenido (ISR + webhook)

Con ISR, **ya no es necesario un rebuild completo** (ni Deploy Hook) para que
el contenido nuevo aparezca en la web:

- **Fallback time-based**: cada página se revalida como máximo cada 1 hora
  (`revalidate: 3600`).
- **Invalidación inmediata**: al publicar/despublicar desde Android, el backend
  dispara un webhook fire-and-forget a `POST https://<web>.vercel.app/api/revalidate`
  con header `X-Revalidate-Secret` (SHA-256 de `REVALIDATE_SECRET`). La web
  invalida el tag `posts` (`revalidateTag('posts', { expire: 0 })`) y las
  siguientes peticiones sirven contenido fresco.

> El script `scripts/deploy-web.sh` y el Deploy Hook de Vercel **ya no se usan
> para actualizar contenido**. Solo sirven para desplegar cambios de código
> (push a `main` ya hace lo mismo).

### Variables de entorno en Render (para el webhook)

En el servicio `moonblogger-api` (Render → Environment), añadir:

| Variable | Valor |
|---|---|
| `WEB_REVALIDATE_URL` | `https://<proyecto>.vercel.app/api/revalidate` |
| `WEB_REVALIDATE_SECRET` | **Mismo string aleatorio que `REVALIDATE_SECRET` en Vercel** |

El backend solo dispara el webhook si ambas están definidas (guard para
dev/tests).

---

## 4. Android: APK release firmado

1. Generar el keystore de firma (solo la primera vez):

   ```bash
   ./scripts/create-keystore.sh
   ```

   Crea `android/moonblogger-release.jks` y `android/keystore.properties`
   (ambos NO versionados). **Custodiar** el keystore y sus contraseñas: son la
   identidad de firma; perderlos impide actualizar la app sobre la instalada.

2. Configurar la URL real de la API para release en `android/local.properties`
   (NO versionado):

   ```
   moonblogger.apiBaseUrlRelease=https://moonblogger-api.onrender.com/
   ```

3. Compilar:

   ```bash
   cd android
   ./gradlew assembleRelease
   ```

   APK firmado: `android/app/build/outputs/apk/release/app-release.apk`.

4. Instalar en el dispositivo de Moon (`adb install -r` o copiando el APK).
   Requiere permitir instalación de orígenes desconocidos en el dispositivo.

---

## 5. Backups

Supabase Free **no incluye backups automáticos**. Hacer volcados con
`scripts/backup.sh` y guardarlos fuera de la infraestructura (offsite):

```bash
DB_HOST=aws-<region>.pooler.supabase.com \
DB_PORT=5432 \
DB_NAME=postgres \
DB_USER=postgres.<project-ref> \
DB_PASSWORD=<password> \
DB_SSLMODE=require \
BACKUP_DIR=<destino> \
./scripts/backup.sh
```

- Requiere el cliente `pg_dump` (paquete `postgresql-client`) en la máquina,
  **versión 17 o superior** (Supabase Free usa PostgreSQL 17).
- Formato custom PostgreSQL (`-Fc`); restauración con `pg_restore`.
- Retención: `BACKUP_RETENTION_DAYS` (default 30).
- El destino final del dump (copia manual, otro proveedor, etc.) lo decide el
  usuario. Se recomienda una frecuencia razonable (p. ej. semanal).

---

## 6. Media: Supabase Storage (límites free)

Media está implementada para imágenes y usa **Supabase Storage**, no el
filesystem efímero de Render. La API emite signed upload URLs y no hace proxy
de bytes. El flujo de imágenes fue validado en producción; el vídeo MP4 con
póster está contratado por backend, pero no validado E2E desde Android.

- El backend crea la signed upload URL con `POST
  /storage/v1/object/upload/sign/{private_bucket}/{key}` y cuerpo `{}`; el
  cliente hace `PUT` con el `Content-Type` declarado exactamente.
- `MEDIA_INTENT_TTL_SECONDS` es el plazo interno para `complete`/limpieza, no
  un TTL enviado a Supabase. Al completar, el backend comprueba el objeto con
  `HEAD /storage/v1/object/{bucket}/{key}` y contrasta `Content-Length` y
  `Content-Type` reales.
- La eliminación en Storage usa `DELETE /storage/v1/object/{bucket}` con
  `{"prefixes":[key]}`. No exponer claves de objetos ni credenciales en
  clientes o documentación operativa.

Límites del plan **Supabase Free** (agosto 2026):

| Límite | Valor | Nota |
|---|---|---|
| Cuota total proyecto | **1 GB** | Imágenes + video |
| Tamaño máx. por archivo | **50 MB** | Video grande imposible en free |
| Egress/mes | **5 GB** | Suma con Vercel (~10 GB/mes combinado) |

> **Video en free:** 50 MB/archivo + 1 GB total = solo clips muy cortos. Video
> serio requerirá plan pago o migración a Cloudflare R2 / S3.

---

## Operación diaria

### Publicar contenido

1. Moon publica/edita desde la app Android (API en Render, BD en Supabase).
2. Render invalida el tag `posts` en Vercel por webhook; la siguiente petición
   sirve contenido fresco. Un push a GitHub solo es necesario para cambios de
   código.

### Ping anti-pausa

Render duerme el servicio tras **15 min** sin tráfico (cold start ~30-60 s en
el siguiente request) y Supabase pausa el proyecto tras 7 días sin actividad
(reanuda solo al recibir una petición). Para un blog personal es tolerable,
pero para reducir el cold start y evitar la pausa se puede configurar un
servicio gratuito de monitoreo (p. ej. cron-job.org) que haga una petición
periódica a `https://moonblogger-api.onrender.com/api/v1/public/posts/` cada
**~10 minutos**. Este endpoint es público y consulta la BD, así que el ping
mantiene despiertos tanto el contenedor de Render como Supabase (el
`/api/v1/health/` no toca la BD y NO evita la pausa de Supabase). Con ese
ritmo, el consumo de horas de instancia (~720-744 h/mes) queda bajo el límite
de 750 h/mes del plan free.

### Verificación de salud

- API: `GET /api/v1/health/` → `{"status": "ok"}`.
- Web: `GET /` responde con el listado de publicaciones publicadas.

### Escaneo de secretos

GitHub Actions ejecuta Gitleaks en cada `push`, Pull Request y ejecución manual.
La configuración está en `.gitleaks.toml`; las exclusiones solo cubren fixtures
de tests y placeholders documentales, nunca credenciales reales.

Para revisar localmente antes de subir cambios, instalar Gitleaks y ejecutar:

```bash
gitleaks dir --redact .
gitleaks git --redact .
```

`gitleaks dir` revisa el contenido actual del directorio, mientras `gitleaks
git` revisa el historial. Si aparece un secreto real, revocarlo o rotarlo
primero; borrar la línea o crear un commit posterior no elimina el valor del
historial ni de clones existentes. Los reportes no deben versionarse porque
pueden contener valores detectados.

### Diagnóstico de `media/complete` en Storage

Si el `PUT` a una URL firmada termina pero `POST .../media/complete/` responde
`503` con `Supabase Storage rechazó HEAD del objeto (HTTP NNN).`, el backend
recibió el rechazo HTTP `NNN` al consultar exactamente
`HEAD /storage/v1/object/{bucket}/{path}`. Ese endpoint obtiene `Content-Length`
y `Content-Type` del asset privado real; no se usa `GET /object/info`. El cliente
solo recibe el estado de proveedor: no se exponen URL firmada, clave del
objeto, service role ni cuerpo de respuesta de Storage. En los logs de Render
se registra de forma acotada `operation=object_info`, el estado HTTP y, cuando
Storage los proporciona y son válidos, su `provider_code` y `request_id`; usar
esos datos para investigar la configuración/permisos de Storage.

Si no se puede conectar a Storage, `complete` informa que Storage no está
disponible para verificar el objeto. Si la respuesta HEAD no contiene un
`Content-Length` entero no negativo y un `Content-Type` no vacío, informa que
los encabezados del objeto son inválidos. Ambos diagnósticos son seguros y no
incluyen URL, claves, service role ni cuerpo del proveedor.

### Migraciones futuras

Tras un cambio de modelo, aplicar migraciones contra la BD de producción desde
una máquina con el código (igual que en la creación inicial):
`.venv/bin/python manage.py migrate` con las variables `DB_*` de Supabase.

---

## Lista de control del primer despliegue

- [ ] Repositorio en GitHub con el proyecto.
- [ ] Proyecto Supabase creado; credenciales del pooler anotadas.
- [ ] `migrate` y `create_moon_user` ejecutados contra la BD de producción.
- [ ] Servicio en Render desplegado y `/api/v1/health/` y `/api/v1/public/posts/`
      responden.
- [ ] Web en Vercel desplegada (root `web`, `API_BASE_URL` + `SITE_URL`).
- [ ] Webhook de revalidación probado al publicar y retirar un post.
- [ ] Keystore generado y custodiado; APK release firmado e instalado en el
      dispositivo de Moon con la URL real de la API.
- [ ] Primer backup de producción ejecutado y guardado offsite.
# Visitas

En producción configure `VISIT_FORWARDING_SECRET` con el mismo secreto que el
proxy firmante. Ejecute periódicamente `python manage.py cleanup_public_visits`.
