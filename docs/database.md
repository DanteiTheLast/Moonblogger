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
