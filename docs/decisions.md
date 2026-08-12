# MoonBlogger — Decisiones técnicas

Formato: registro de decisiones. Cada entrada indica qué se decidió, por qué y
qué alternativas se consideraron.

## D1 — Autenticación: JWT con SimpleJWT

- **Decisión (11/08/2026):** JWT con `djangorestframework-simplejwt`.
  Access 15 min, refresh 7 días con rotación. Aprobado por el usuario.
- **Por qué:** estándar de facto para API móvil, sin CSRF, encaja con
  Retrofit/OkHttp en Android; coste de implementación bajo.
- **Alternativas:** token estático de DRF (más simple pero sin expiración ni
  revocación real) y sesiones Django (cómodas en web, incómodas en Android).
- **Nota (blacklist, 11/08/2026):** la rotación usa blacklist
  (`BLACKLIST_AFTER_ROTATION = True`). Al refrescar, el refresh anterior queda
  en la tabla `token_blacklist_blacklistedtoken` y su reuso devuelve `401`
  (`"Token is blacklisted"`). Se añadieron las tablas
  `token_blacklist_outstandingtoken` y `token_blacklist_blacklistedtoken`.
  Los refresh emitidos antes de activar la blacklist no constan en ella:
  conservan validez residual hasta su expiración natural (≤ 7 días) y quedan
  blacklisted en su primer uso de refresh.

## D2 — Stack web: Next.js + TypeScript con SSG

- **Decisión (11/08/2026):** Next.js + TypeScript con pre-renderizado estático
  (SSG). Aprobado por el usuario.
- **Por qué:** contenido público de solo lectura, ideal para pre-renderizar en
  build; mantiene el stack definido para el agente frontend; despliegue
  estático simple.
- **Alternativas:** estático servido por Django (un solo servicio/despliegue,
  pero fija la web al backend) y Astro (muy adecuado pero framework distinto).

## D3 — Estado de publicación: `status`

- **Decisión (11/08/2026):** campo `status` con valores `draft` / `published`
  (default `draft`), en modelo y API. Aprobado por el usuario.
- **Por qué:** más legible en logs, extensible a futuro, sin ambigüedad del
  booleano (`is_published=false` = ¿borrador o retirado?).
- **Alternativa:** booleano `is_published`.

## D4 — URLs de detalle web: por slug

- **Decisión (11/08/2026):** la web usa rutas `/posts/[slug]`; el modelo incluye
  un campo `slug` único auto-generado desde el título (con desempate). Aprobado
  por el usuario.
- **Por qué:** URLs legibles para compartir.
- **Alternativa:** URLs por `id` (sin campos extra en el modelo).

## D5 — Modelo Post con FK a usuario

- **Decisión:** incluir `author` (FK a `auth.User`) aunque haya una sola cuenta.
- **Por qué:** reutiliza la autenticación/permisos de Django, habilita la
  autorización por dueño, y evita una migración de datos (backfill) si algún
  día hay más cuentas.
- **Alternativa:** sin FK (mínimo absoluto, deuda futura).

## D6 — PostgreSQL local con Docker Compose

- **Decisión:** contenedor PostgreSQL 16 gestionado por `docker-compose.yml`,
  con rol dedicado `moonblogger` y credenciales por variables de entorno.
- **Por qué:** paridad dev/prod, reproducible, sin instalar PostgreSQL en el
  host.
- **Nota:** la BD se usa desde la Etapa 1 (no SQLite intermedio).

## D7 — Stack Android

- **Decisión:** Kotlin + Jetpack Compose (Material 3), Navigation Compose,
  Retrofit + OkHttp, kotlinx.serialization, corrutinas, ViewModel + StateFlow,
  e inyección de dependencias manual (sin Hilt) en v1.
- **Por qué:** estándar Android; DI manual suficiente para ~4 pantallas.
- **Nota:** token JWT en almacenamiento cifrado (`security-crypto`, con deuda
  técnica anotada por su deprecación upstream).

## D8 — Reglas de datos v1

- Ordenación por fecha (creación/publicación) descendente, con `id` como
  desempate.
- Borrado físico (los backups cubren la recuperación).
- Sin índices adicionales en v1 (volumen bajo); candidato futuro documentado:
  `(status, published_at)` para la consulta pública.
- Sin unicidad en el título (el `slug` sí es único).

## D9 — Configuración y secretos

- Toda configuración/secreto por variables de entorno.
- `.env.example` versionado; `.env` real en `.gitignore`.
- Backend: `DJANGO_SECRET_KEY`, `DJANGO_DEBUG`, `DJANGO_ALLOWED_HOSTS`,
  `DB_*`, `CORS_ALLOWED_ORIGINS`.
- Android: base URL por build type (`10.0.2.2` en debug; dominio real en
  release).

## D10 — Versiones objetivo

- Python 3.12/3.13, Django 5.x LTS, PostgreSQL 16, Kotlin/AGP estables.
  Las versiones exactas se verifican al momento de crear cada componente.
