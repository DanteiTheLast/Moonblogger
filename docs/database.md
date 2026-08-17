# MoonBlogger — Base de datos

> Diseño acordado (decisiones D3, D5, D8). Las migraciones las genera Django
> (agente Backend); el DDL se aplica exclusivamente vía `manage.py migrate`.

## Entidad Post

| Campo | Tipo | Restricciones | Notas |
|---|---|---|---|
| `id` | bigint | PK | Auto-incremental (PK de Django). |
| `author` | FK → `auth.User` | NOT NULL | `on_delete=CASCADE`, `related_name="posts"`. Aunque haya una sola cuenta. |
| `title` | varchar(200) | NOT NULL | No vacío tras `strip()`. Sin unicidad. |
| `slug` | varchar | UNIQUE, NOT NULL | Auto-generado desde el título (desempate `-2`, `-3`…). |
| `content` | text | NOT NULL | Texto plano. No vacío tras `strip()`. |
| `status` | varchar | NOT NULL, DEFAULT `draft` | `choices=("draft","published")`, `db_index`. |
| `created_at` | timestamptz | NOT NULL | `auto_now_add`. Siempre UTC. |
| `updated_at` | timestamptz | NOT NULL | `auto_now`. Siempre UTC. |
| `published_at` | timestamptz | NULL | Se fija al publicar, se limpia al volver a `draft`. |
| `carousel_transition` | varchar | NOT NULL, DEFAULT `slide` | `slide`, `fade`, `bubble` o `none`. |

## Índices

- PK (`id`) automática.
- Índice sobre `status` (filtro de la web).
- Candidato futuro (no crear en v1, volumen bajo): índice compuesto
  `(status, published_at)` para el listado público.

## Ordenación

- Listado público: `published_at DESC, id DESC`.
- Listado privado: `created_at DESC, id DESC` (o `updated_at` si se prefiere).
  `id` como desempate determinista.

## Reglas

- **Borrado físico** (`DELETE`): los backups (`pg_dump`) cubren la recuperación.
- Validación de no-vacío en el backend (serializer); `NOT NULL` en la BD.

## Multimedia (Etapa 1)

`Post.carousel_transition` es `slide` (default), `fade`, `bubble` o `none`; la
migración conserva los posts existentes con `slide` y sin media.

`PostMedia` usa UUID como PK y FK a `Post`. Conserva únicamente metadatos y
claves de objeto, nunca bytes ni credenciales: tipo (`image`/`video`), estado
(`pending`/`ready`/`failed`), posición opcional, portada, claves privada/pública
y de póster, MIME/tamaño de asset y póster, dimensiones, duración declarada, texto alternativo,
caption y timestamps de vencimiento/listo. Hay unicidad de posición activa por
post, una sola portada por post e índices para layout y limpieza de intents.

`StorageDeletionTask` es una cola persistente de borrado (`bucket`, clave,
intentos, error y finalización). Al borrar media o posts se encola su limpieza;
`manage.py cleanup_media_storage` borra intents vencidos y reprocesa las tareas
pendientes de manera idempotente. No requiere scheduler externo, aunque en
producción debe invocarse periódicamente por el mecanismo operativo elegido.

## Tablas de la blacklist de JWT (SimpleJWT)

Creadas por las migraciones de `rest_framework_simplejwt.token_blacklist`
(activada con `BLACKLIST_AFTER_ROTATION = True`, ver decisión D1).

| Tabla | Descripción | Restricciones clave |
|---|---|---|
| `token_blacklist_outstandingtoken` | Refresh tokens emitidos y aún no usados (o emitidos antes de la blacklist). | `jti` con índice único (`..._jti_hex_..._uniq`); FK `user` → `auth.User`. |
| `token_blacklist_blacklistedtoken` | Refresh tokens ya rotados/invalidados (prohibidos). | FK `token` → `token_blacklist_outstandingtoken`, única. |

- El reuso de un refresh blacklisted devuelve `401` (`"Token is blacklisted"`).
- **Mantenimiento futuro:** limpieza de tokens vencidos con
  `manage.py flushexpiredtokens` (no ejecutado en v1).

## Entorno local

- Contenedor PostgreSQL 16 (`docker-compose.yml`) con rol dedicado
  `moonblogger`, base `moonblogger`.
- Credenciales por variables de entorno (`.env` local, `.env.example`
  versionado). Parámetros Django: `DB_NAME`, `DB_USER`, `DB_PASSWORD`,
  `DB_HOST`, `DB_PORT`.

## Backups

Copia de seguridad lógica con `scripts/backup.sh` (usa `pg_dump -Fc`,
sin dependencias extra). Genera un volcado comprimido con nombre
`moonblogger_YYYYMMDD_HHMMSS.dump` y aplica retención por días.

- **Local (docker-compose):** con la BD levantada y las credenciales de
  `docker-compose.yml` (`.env`):

  ```bash
  DB_PASSWORD=<password> ./scripts/backup.sh
  ```

- **Producción (Supabase):** variables según el panel de Supabase
  (Project Settings → Database):

  ```bash
  DB_HOST=<pooler-session-o-conexion-directa> \
  DB_PORT=5432 \
  DB_NAME=postgres \
  DB_USER=postgres.<project-ref> \
  DB_PASSWORD=<password> \
  DB_SSLMODE=require \
  ./scripts/backup.sh
  ```

  `DB_SSLMODE=require` es el modo usado por el pooler *session* de
  Supabase; la conexión directa (`db.<project-ref>.supabase.co`) también
  requiere SSL.

**Requisito de versión:** Supabase Free usa PostgreSQL 17; el cliente
`pg_dump` debe ser de versión **igual o superior a la del servidor**
(17+) o el volcado falla por "server version mismatch". Instala
`postgresql-client` 17 o usa un contenedor `postgres:17`. El modo SSL se
aplica vía la variable de entorno `PGSSLMODE` de libpq (el flag
`--sslmode` de `pg_dump` no existe en todas las builds).

Variables del script (con defaults):

| Variable | Default | Descripción |
|---|---|---|
| `DB_HOST` | `localhost` | Host de PostgreSQL. |
| `DB_PORT` | `5432` | Puerto. |
| `DB_NAME` | `moonblogger` | Nombre de la base. |
| `DB_USER` | `moonblogger` | Rol de conexión. |
| `DB_PASSWORD` | — (obligatoria) | Contraseña; se pasa a `pg_dump` vía `PGPASSWORD` sin imprimirse. |
| `DB_SSLMODE` | (vacío) | Modo SSL vía `PGSSLMODE` de libpq; `require` para Supabase. |
| `BACKUP_DIR` | `./backups` (relativo al raíz del repo) | Directorio de salida. |
| `BACKUP_RETENTION_DAYS` | `30` | Borra dumps más antiguos que N días. |

**Importante:** el plan Free de Supabase **no incluye backups
automáticos** y el proyecto se pausa tras 7 días sin actividad. El dump
generado debe copiarse fuera de la infraestructura (offsite); el destino
final (copia manual, almacenamiento propio, otro proveedor…) lo decide el
usuario. El volcado está en formato custom de PostgreSQL y se restaura con
`pg_restore`.
