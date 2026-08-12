# MoonBlogger — Despliegue y operación

Guía de despliegue a producción en planes gratuitos y de operación diaria.
Precios y límites son los de los planes free (Koyeb Free, Supabase Free,
Vercel Hobby) en el momento de escribir este documento (11/08/2026).

## Arquitectura desplegada

| Capa | Plataforma | Qué se ejecuta | Coste |
|---|---|---|---|
| API | Koyeb Free | Django + DRF (gunicorn + WhiteNoise), imagen desde `backend/Dockerfile` | $0 |
| Base de datos | Supabase Free | PostgreSQL gestionado (session pooler) | $0 |
| Web | Vercel Hobby | Next.js SSG (`output: 'export'`, carpeta `out/`) | $0 |
| Android | Dispositivo de Moon | APK release firmado, instalado directamente | $0 |

## Antes de empezar (prerrequisitos)

- Cuentas creadas: **GitHub**, **Koyeb**, **Supabase**, **Vercel**.
- Repositorio en GitHub con este proyecto (Koyeb y Vercel despliegan desde él).
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

## 2. API: Koyeb Free

### Crear el servicio

1. En Koyeb → **Create Service** → GitHub repository (repositorio de
   MoonBlogger).
2. **Builder**: Dockerfile.
   - **Build context / working directory**: `backend` (el Dockerfile está en
     `backend/Dockerfile`).
3. **Health check**: rutar contra `/api/v1/health/` (no requiere auth).
4. **Variables de entorno** (production):

   | Variable | Valor |
   |---|---|
   | `DJANGO_DEBUG` | `false` |
   | `DJANGO_SECRET_KEY` | valor seguro, largo y aleatorio (no compartido) |
   | `DJANGO_ALLOWED_HOSTS` | subdominio del servicio, p. ej. `<servicio>.<org>.koyeb.app` |
   | `DB_HOST` | `aws-<region>.pooler.supabase.com` |
   | `DB_PORT` | `5432` |
   | `DB_NAME` | `postgres` (o el nombre que muestre Supabase) |
   | `DB_USER` | `postgres.<project-ref>` |
   | `DB_PASSWORD` | password de la BD de Supabase |
   | `DB_SSLMODE` | `require` |
   | `CORS_ALLOWED_ORIGINS` | orígenes: `https://<servicio>.<org>.koyeb.app` y `https://<proyecto>.vercel.app` |

   `PORT` lo inyecta Koyeb; gunicorn escucha en `0.0.0.0:${PORT:-8000}`.
   Con `DJANGO_DEBUG=false`, Koyeb exige `DJANGO_SECRET_KEY` y
   `DJANGO_ALLOWED_HOSTS` (fail-fast: el arranque falla si faltan).

5. Tras el despliegue, el servicio queda en
   `https://<servicio>.<org>.koyeb.app`. Verificar: `GET /api/v1/health/`
   → `{"status": "ok"}` y `GET /admin/` (login) responden.

> Los estáticos del admin los sirve WhiteNoise (`collectstatic` se ejecuta en
> el build de la imagen); no hace falta servidor de estáticos aparte.

---

## 3. Web: Vercel Hobby

1. En Vercel → **Add New → Project** → importar el repositorio de MoonBlogger.
2. **Root Directory**: `web` (el monorepo también contiene `backend/` y
   `android/`).
3. **Environment Variables**:
   - `API_BASE_URL` → `https://<servicio>.<org>.koyeb.app/api/v1` (sin barra
     final).
   - `SITE_URL` → la URL pública de la web, p. ej.
     `https://<proyecto>.vercel.app` (sin barra final).
4. **Build Command**: el por defecto de Next (equivale a `npm run build`, que
   ya limpia la caché de fetch para obtener contenido fresco de la API).
5. Deploy. La web queda en `https://<proyecto>.vercel.app`.

### Rebuild al publicar (Vercel Deploy Hook)

Como la web es estática, al publicar/editar contenido hay que reconstruir:

1. En Vercel → **Project (web) → Settings → Git → Deploy Hooks → Create Hook**
   (branch principal). Copiar la URL generada.
2. Guardar esa URL como variable de entorno `VERCEL_DEPLOY_HOOK`.
3. Tras cada publicación en la API, disparar el rebuild:

   ```bash
   VERCEL_DEPLOY_HOOK=https://api.vercel.com/v1/integrations/deploy/<id>/<token> \
     ./scripts/deploy-web.sh
   ```

   (o hacer push a GitHub, que Vercel detecta y reconstruye).

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
   moonblogger.apiBaseUrlRelease=https://<servicio>.<org>.koyeb.app/
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

## Operación diaria

### Publicar contenido

1. Moon publica/edita desde la app Android (API en Koyeb, BD en Supabase).
2. Si la web debe actualizarse: disparar `scripts/deploy-web.sh` (Deploy Hook)
   o hacer push a GitHub.

### Ping anti-pausa

Koyeb escala a 0 tras 1 h sin tráfico (cold start ~30 s al siguiente request)
y Supabase pausa el proyecto tras 7 días sin actividad (reanuda solo al recibir
una petición). Para un blog personal es tolerable, pero si se quiere reducir el
cold start y evitar la pausa, se puede configurar un servicio gratuito de
monitoreo (p. ej. cron-job.org) que haga una petición periódica a
`https://<servicio>.<org>.koyeb.app/api/v1/health/` cada pocos minutos. Ese
ping mantiene despiertos tanto el contenedor de Koyeb como la BD de Supabase.

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
- [ ] Servicio en Koyeb desplegado y `/api/v1/health/` responde.
- [ ] Web en Vercel desplegada (root `web`, `API_BASE_URL` + `SITE_URL`).
- [ ] Deploy Hook creado y probado con `scripts/deploy-web.sh`.
- [ ] Keystore generado y custodiado; APK release firmado e instalado en el
      dispositivo de Moon con la URL real de la API.
- [ ] Primer backup de producción ejecutado y guardado offsite.
