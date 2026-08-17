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

## D2 — Stack web: Next.js + TypeScript con ISR + webhook (12/08/2026)

- **Decisión (12/08/2026):** Next.js + TypeScript con **ISR (Incremental Static Regeneration)** y revalidación bajo demanda por webhook desde el backend. Aprobado por el usuario.
- **Por qué:** SSG puro requería rebuild completo en Vercel (Deploy Hook) tras cada publicación; ISR + `revalidateTag` permite invalidación selectiva y inmediata de la caché solo cuando cambia el conjunto público (publicar/despublicar), sin rebuilds ni esperas. El webhook es fire-and-forget, no bloquea el CRUD, y usa tag-based revalidation para no sobrecargar la API en cold start.
- **Alternativas consideradas:**
  - SSG + Deploy Hook (D2 original): rebuild completo, latencia de minutos, consumo de builds de Vercel.
  - SSR completo: consumo innecesario de funciones serverless en plan free; cold start de Render en cada request.
  - Polling / SWR en cliente: no resuelve SEO/sitemap y añade complejidad cliente.
- **Detalles de implementación:**
  - `next.config.ts`: sin `output: 'export'` (ISR requiere funciones serverless).
  - Fetch con `next: { revalidate: 3600, tags: ['posts'] }` en `lib/api.ts`.
  - Páginas: `export const revalidate = 3600` en `/`, `/posts/[slug]`, `sitemap.ts`.
  - `generateStaticParams` con try/catch → `[]` si la API falla en build (no bloquea deploy).
  - Endpoint `POST /api/revalidate`: valida `X-Revalidate-Secret` (SHA-256) → `revalidateTag('posts', { expire: 0 })`.
  - Backend: signals `post_save`/`post_delete` con filtro de transición (solo to/from `published`), `transaction.on_commit` + thread daemon + timeout 3s + guard env vars.
- **Fallback:** revalidate time-based 1 h (`revalidate: 3600`) cubre cualquier fallo del webhook (proceso muerto, red, etc.).
- **Nota (12/08/2026):** Esta decisión **supera a D2 original (SSG)**. El rebuild vía Deploy Hook ya no es necesario para actualizar contenido; queda solo para cambios de código.

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

## D11 — Despliegue de producción (Etapa 5, pre-producción)

- **Decisión (11/08/2026):** despliegue en plataformas en sus planes gratuitos,
  aprobado por el usuario:
  - **Nota histórica:** la parte web de esta decisión (SSG y Deploy Hook) fue
    sustituida por D2. La configuración vigente usa ISR y webhook.
  - **API (Django + DRF)** → **Koyeb Free** (Dockerfile, gunicorn + WhiteNoise,
    subdominio `{{KOYEB_PUBLIC_DOMAIN}}`, SSL incluido). Escala a 0 tras 1h sin
    tráfico (cold start ~30 s en el siguiente request).
  - **PostgreSQL** → **Supabase Free** (session pooler: `aws-<region>.pooler
    .supabase.com:5432`, usuario `postgres.<project-ref>`, `sslmode=require`).
    El proyecto se pausa tras 7 días sin actividad y se reanuda con la siguiente
    petición. Sin backups automáticos en el plan free → `scripts/backup.sh`.
  - **Web (Next.js)** → **Vercel Hobby**: SSG puro con `output: 'export'`
    (carpeta `out/`), sitemap/robots estáticos y **rebuild al publicar** vía
    Vercel Deploy Hook (`scripts/deploy-web.sh`).
  - **Android** → APK release firmado, instalación directa en el dispositivo de
    Moon (sin Play Store). Keystore local generado y custodiado por el usuario
    (`scripts/create-keystore.sh`, `android/keystore.properties` gitignored).
- **Por qué:** coste $0, SSL y dominio de subnivel incluidos, cobertura de las
  tres capas con herramientas mantenidas.
- **Alternativas consideradas:** VPS propio (control total pero mantenimiento y
  coste), Railway/Render (similares a Koyeb), base de datos gestionada de Koyeb
  (incluida en free pero con la misma política de pausa). Supabase se eligió por
  su plan free conocido y su CLI de backups.
- **Tradeoffs aceptados (documentados en `docs/deployment.md`):** cold start de
  Koyeb, pausa de Supabase a la semana (mitigación: ping periódico), y que el
  contenido web se actualiza con cada rebuild (no en tiempo real).
- **Dominio propio:** diferido; se usan los subdominios gratuitos. Todo está
  parametrizado por env (`DJANGO_ALLOWED_HOSTS`, `CORS_ALLOWED_ORIGINS`,
  `API_BASE_URL`, `SITE_URL`, `moonblogger.apiBaseUrlRelease`) para incorporar
  un dominio propio más adelante sin cambios de código.
- **Nota (11/08/2026):** en lo relativo a la API, esta decisión queda superada
  por [D12](#d12--migración-de-la-api-a-render-free-11082026).

## D12 — Migración de la API a Render Free (11/08/2026)

- **Decisión:** la API (Django + DRF) pasa de **Koyeb Free** a **Render Free**
  (web service Docker, `backend/Dockerfile`, gunicorn + WhiteNoise, subdominio
  `https://<servicio>.onrender.com`, SSL incluido), definido como Blueprint
  (`render.yaml` en la raíz). **Supera a D11 en lo relativo a la API**; el resto
  del stack no cambia (Supabase Free, Vercel Hobby, APK firmado).
- **Por qué:** Koyeb cerró su plan gratuito a nuevos usuarios. Render ofrece un
  plan free sin tarjeta con despliegue por Dockerfile equivalente (sin cambios
  en la imagen), SSL/subdominio incluidos y health checks configurados en el
  blueprint. `DJANGO_ALLOWED_HOSTS` se resuelve en código desde
  `RENDER_EXTERNAL_HOSTNAME` (Render no interpola variables de entorno).
- **Alternativas:** Railway (sin plan free comparable), Fly.io (requiere
  tarjeta), VPS propio (mantenimiento y coste, ya descartado en D11). Mantener
  Koyeb no era viable (plan cerrado a nuevos usuarios).
- **Tradeoffs:** spin-down tras **15 min** de inactividad (Koyeb: 1 h) con cold
  start ~30-60 s; límite **750 h/mes** de instancia; filesystem efímero (sin
  impacto: no hay media/uploads, los estáticos los sirve WhiteNoise desde la
  imagen y la BD está en Supabase). Mitigaciones: ping anti-pausa cada ~10 min
  a `/api/v1/public/posts/` (mantiene Render y Supabase activos; `/health/` no
  toca la BD), timeouts ampliados en Android (connect 30 s / read 90 s) y web
  (timeout 90 s + retry) para absorber el cold start. Región se elige al crear
  el blueprint (recomendada: cercana a Supabase `ca-central-1`).

## D13 — Media: Supabase Storage (Etapa 1, 12/08/2026; actualizada 16/08/2026)

- **Decisión:** imágenes y vídeo MP4 se guardan en **Supabase Storage**, nunca
  en el filesystem efímero de Render. Metadatos y outbox de limpieza viven en
  PostgreSQL. La API emite signed upload URLs; no hace proxy de bytes.
- **Por qué:** Render Free tiene filesystem efímero (se pierde en cada deploy/spin-up) y un solo worker; hacer proxy de archivos grandes agotaría el worker y excedería memoria/tiempo. Supabase Storage es nativo, gratis en plan free (1 GB), y signed URLs delegan la transferencia al cliente directamente contra el storage.
- **Límites free documentados:**
  - Cuota total: **1 GB** (proyecto Supabase).
  - Tamaño máximo por archivo: **50 MB** (video grande imposible en free).
  - Egress: **5 GB/mes** (plan Supabase Free) + 5 GB/mes (plan Vercel Hobby) = 10 GB/mes combinado aprox.
- **Implementación Etapa 1:** bucket privado para borradores, bucket público
  para assets activos publicados, claves UUID inmutables y copia privada→pública
  antes de publicar. La retirada encola la eliminación pública; su efecto puede
  retrasarse por caché CDN. El comando `cleanup_media_storage` reprocesa la cola
  y limpia intents vencidos.
- **Tradeoff operativo:** la promoción sigue siendo síncrona para mantener el
  contrato de publicación (si Storage falla, no se publica). La publicación de
  un post lista media sin `select_for_update` antes de copiar; el cambio de
  layout sí conserva su bloqueo para mantener posiciones/portada atómicas y por
  tanto puede esperar Storage. Copias parciales se encolan en el outbox para
  reconciliación mediante `cleanup_media_storage`; una futura cola asíncrona
  puede eliminar esta latencia si el volumen lo exige.
- **Alternativas:** filesystem local (no persiste en Render), Cloudflare R2 (requiere cuenta separada, añade complejidad), proxy en API (bloquea worker).
- **Tradeoffs:** 50 MB/archivo y 1 GB total limitan a imágenes y video muy corto; video serio requerirá plan pago o R2.
