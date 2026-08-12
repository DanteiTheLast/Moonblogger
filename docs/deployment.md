# MoonBlogger — Despliegue y operación

Guía de despliegue a producción en planes gratuitos y de operación diaria.
Precios y límites son los de los planes free (Render Free, Supabase Free,
Vercel Hobby) en el momento de escribir este documento (11/08/2026).

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

Cuando se implemente media (FileFields en modelos), los archivos vivirán en
**Supabase Storage**, no en el filesystem de Render (efímero). La API emitirá
signed URLs (delegación); no hará proxy de bytes.

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
2. Si la web debe actualizarse: disparar `scripts/deploy-web.sh` (Deploy Hook)
   o hacer push a GitHub.

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
- [ ] Deploy Hook creado y probado con `scripts/deploy-web.sh`.
- [ ] Keystore generado y custodiado; APK release firmado e instalado en el
      dispositivo de Moon con la URL real de la API.
- [ ] Primer backup de producción ejecutado y guardado offsite.
