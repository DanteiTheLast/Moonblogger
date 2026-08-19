# MoonBlogger — Contrato de API

> **Estado:** implementado en la **Etapa 1 (backend)**. Cualquier cambio posterior
> se coordina con Android y Web. Prefijo real: `/api/v1/` (ver `backend/posts/`).

- Prefijo de versión: `/api/v1/`.
- Formato: JSON. Errores con el formato por defecto de DRF
  (`400 → {"campo": ["mensaje"]}`, `401/403/404 → {"detail": "..."}`).
- Timestamps en ISO 8601 (UTC). Paginación de DRF: `{count, next, previous, results}`.

## Autenticación (JWT)

| Método | Ruta | Acceso | Entrada | Respuesta |
|---|---|---|---|---|
| POST | `/api/v1/auth/login/` | público | `{username, password}` | `{access, refresh}` |
| POST | `/api/v1/auth/refresh/` | público | `{refresh}` | `{access, refresh}` |

- La rotación de refresh tokens está activa (`ROTATE_REFRESH_TOKENS = True`) y usa
  blacklist (`BLACKLIST_AFTER_ROTATION = True`): al usar `/api/v1/auth/refresh/`,
  el refresh token anterior queda invalidado y la respuesta incluye el nuevo
  refresh. Los clientes deben persistir el refresh token de cada respuesta de
  refresh para usarlo en la siguiente llamada. Reusar un refresh ya rotado
  devuelve `401` con `{"detail": "Token is blacklisted"}`.

## Posts — privado (Android, autenticado)

## Public visits — interno

`POST /api/v1/internal/public-visits/` recibe exactamente `{path,event_id}`.
La firma HMAC usa `timestamp\nevent_id\nip\npath\nua`; timestamp es Unix en
string, IP se canonicaliza con `ipaddress`, y User-Agent se compacta (whitespace)
y trunca a 512 bytes/caracteres antes de firmar y persistir. El endpoint verifica
`X-Visitor-User-Agent` saneado y `X-Visitor-IP` canonicalizada, no acepta IP en el
body, deduplica UUIDs y aplica 30 eventos/minuto por IP. Los detalles no se
devuelven; replay válido responde 204 sin incrementar.

| Método | Ruta | Entrada | Respuesta |
|---|---|---|---|
| GET | `/api/v1/posts/` | `?status=draft\|published` (opcional) | Lista paginada (incluye borradores) |
| POST | `/api/v1/posts/` | `{title, content, status, carousel_transition?}` | Post creado (201) |
| GET | `/api/v1/posts/{id}/` | — | Post completo |
| PUT/PATCH | `/api/v1/posts/{id}/` | `{title, content, status, carousel_transition?}` (PATCH parcial) | Post actualizado |
| DELETE | `/api/v1/posts/{id}/` | — | 204 |

## Posts — público (Web, solo lectura)

| Método | Ruta | Entrada | Respuesta |
|---|---|---|---|
| GET | `/api/v1/public/posts/` | — | Lista paginada, **solo `status=published`** |
| GET | `/api/v1/public/posts/{slug}/` | — | Post publicado (404 si es borrador) |

## Objeto Post

```json
{
  "id": 1,
  "slug": "mi-primer-post",
  "title": "Mi primer post",
  "content": "Texto plano...",
  "status": "draft",
  "created_at": "2026-08-11T12:00:00Z",
  "updated_at": "2026-08-11T12:30:00Z",
  "published_at": null
}
```

- `published_at` se fija automáticamente al pasar a `published` y se limpia al
  volver a `draft`.
- El `slug` se auto-genera desde el título (único, con desempate `-2`, `-3`…).
- Listado público ordenado por `published_at` desc; listado privado por
  `updated_at` (o `created_at`) desc, con `id` como desempate.

## Multimedia de posts — privado (aditivo, Etapa 1)

Estas rutas requieren JWT y devuelven `404` tanto para un post/media ajeno como
inexistente. Django **no** recibe bytes: el cliente carga directamente a la URL
efímera de Supabase. El flujo de imágenes está validado E2E; el contrato de
vídeo/póster sigue sin validación E2E desde Android.

| Método | Ruta | Entrada | Respuesta |
|---|---|---|---|
| POST | `/api/v1/posts/{id}/media/upload-intents/` | `kind`, `mime_type`, `size_bytes`, `width?`, `height?`; vídeo: `duration_seconds`, `poster_mime_type`, `poster_size_bytes` | `201 {media_id, upload_url, poster_upload_url?, expires_at}` |
| POST | `/api/v1/posts/{id}/media/complete/` | `{media_id}` | Confirma metadata remota y marca `ready`. |
| PATCH | `/api/v1/posts/{id}/media/{media_id}/` | `{alt_text?, caption?}` | Metadata privada actualizada. |
| DELETE | `/api/v1/posts/{id}/media/{media_id}/` | — | `204`; encola limpieza de objetos. |
| PUT | `/api/v1/posts/{id}/media/layout/` | `{items:[{id,position,is_cover}], carousel_transition?}` | Layout atómico. |
| GET | `/api/v1/posts/{id}/media/read-urls/` | — | `200 {expires_at, media:[{id,url,poster_url?}]}`; URLs privadas firmadas. |

- Máximo 10 elementos por post y 2 vídeos. Imágenes: JPEG/PNG/WebP hasta 8 MiB;
  vídeo: MP4 hasta 40 MiB y duración declarada máxima de 120 s. Todo vídeo exige
  un póster de imagen en el intent.
- Cada intent usa claves inmutables generadas con UUID y sin `upsert`.
  Antes de crear un intent autenticado válido, la API ejecuta housekeeping
  acotado (hasta 10 intents vencidos y 10 tareas de borrado). Es interno: no
  cambia el contrato ni la respuesta; si el borrado remoto falla, la tarea queda
  pendiente para reintento y no bloquea un intent que pueda obtener su URL.
  `MEDIA_INTENT_TTL_SECONDS` (el alias anterior `MEDIA_UPLOAD_TTL_SECONDS` se
  acepta) es el plazo interno para `complete` y limpieza; no revoca una URL
  firmada que Supabase ya haya emitido y no se transmite como `expiresIn` a
  Supabase. `complete` consulta exactamente `HEAD /storage/v1/object/{bucket}/{path}`
  con las credenciales de service role y compara los headers del asset real
  `Content-Length` y `Content-Type` contra el intent (incluido MIME/tamaño
  exactos del póster). No depende del endpoint JSON `GET /object/info`.
- Solo elementos `ready` pueden entrar al layout. Las posiciones deben ser
  `0..n-1`; un layout no vacío tiene exactamente una portada. Pending/failed no
  se exponen públicamente.
- Al publicar, los elementos activos se copian del bucket privado al público
  antes de cambiar el estado del post. Si Storage falla, el post queda borrador.
  Posts sin media se pueden publicar. Media privada expone estados/metadatos,
  pero nunca claves de objeto, service-role ni credenciales persistentes.

## Multimedia pública

Los listados de `/api/v1/public/posts/` añaden solamente `cover` y
`media_count` (además de los campos del post); el detalle añade `media`, ordenado
por posición. Las URLs `url`/`poster_url` son rutas públicas estables de
Supabase Storage; no se devuelven claves privadas.

## Configuración externa de Storage

El servidor necesita `SUPABASE_STORAGE_URL` (o `SUPABASE_URL`),
`SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_STORAGE_PRIVATE_BUCKET` y
`SUPABASE_STORAGE_PUBLIC_BUCKET`, además de `MEDIA_INTENT_TTL_SECONDS` y los
límites `MEDIA_MAX_*` documentados en `backend/.env.example`. Si faltan, CRUD
de posts sigue disponible y las rutas de media responden `503` explícito.

En Supabase se deben crear los buckets privado y público indicados (el segundo
marcado público), permitir al service role las operaciones Storage y configurar
CORS del bucket/origen para que Android/Web puedan hacer `PUT` a las signed
upload URLs. La service role se configura **solo en el entorno del backend**:
nunca en Android, web ni repositorio.

### Protocolo de carga directa

1. Solicitar el intent; la respuesta contiene una `upload_url` efímera y, para
   vídeo, una `poster_upload_url` efímera. No se persisten ni se reemiten estas
   URLs desde el backend. Internamente, el backend la solicita a Supabase
   mediante `POST /storage/v1/object/upload/sign/{private_bucket}/{key}` con
   cuerpo JSON `{}`. `MEDIA_INTENT_TTL_SECONDS` solo delimita el plazo de la
   aplicación para completar o limpiar el intent; no se envía a Supabase ni
   determina la vigencia de su URL firmada.
2. Hacer `PUT` directamente a cada URL con el cuerpo binario del archivo y el
   header `Content-Type` exactamente igual al MIME declarado. No enviar JSON,
   `multipart/form-data`, ni `x-upsert`: la clave UUID es de una sola creación.
3. Para vídeo, cargar el póster antes de llamar a `complete`.
4. Llamar a `complete` con el `media_id`; solo entonces el elemento puede entrar
   al layout.

Al publicar, las claves inmutables se copian al bucket público. Al despublicar
un post o retirar un asset del layout publicado, el backend borra las claves
públicas mediante outbox y las limpia de la BD; una republicación las copia de
nuevo. CDNs pueden servir una versión en caché transitoriamente hasta que su
TTL propio venza; no es una garantía de revocación instantánea.

La eliminación remota usa el contrato de Supabase
`DELETE /storage/v1/object/{bucket}` con cuerpo `{"prefixes":[key]}`. Las
claves se mantienen internas: no forman parte de la API de MoonBlogger.
# Visitas públicas

`POST /api/v1/internal/public-visits/` acepta exactamente
`{"path":"/","event_id":"<uuid>"}` o un path de post publicado. Requiere
`X-Visitor-IP`, `X-Visitor-User-Agent`, `X-Visitor-Timestamp` y
`X-Visitor-Signature`. La firma HMAC-SHA256 usa las líneas
`timestamp\nevent_id\nip_canonicalizada\npath\nua_saneado`; el timestamp es Unix y
solo se acepta dentro de `VISIT_FORWARDING_MAX_AGE_SECONDS`. La repetición del
mismo `event_id` es idempotente y no incrementa el contador. Devuelve 204; los
errores no revelan si existe un post, una IP, una firma o un secreto.
