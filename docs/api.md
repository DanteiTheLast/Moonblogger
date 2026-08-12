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

| Método | Ruta | Entrada | Respuesta |
|---|---|---|---|
| GET | `/api/v1/posts/` | `?status=draft\|published` (opcional) | Lista paginada (incluye borradores) |
| POST | `/api/v1/posts/` | `{title, content, status}` | Post creado (201) |
| GET | `/api/v1/posts/{id}/` | — | Post completo |
| PUT/PATCH | `/api/v1/posts/{id}/` | `{title, content, status}` (PATCH parcial) | Post actualizado |
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
