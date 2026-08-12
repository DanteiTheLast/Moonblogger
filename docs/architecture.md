# MoonBlogger — Arquitectura

## Diagrama

```
Android ──┐
          ├── HTTPS / REST ──► Django REST API ──► ORM ──► PostgreSQL
Web ──────┘
```

## Topología de producción (Etapa 5)

```
Android (APK firmado, instalado en el dispositivo de Moon)
   │
   │ HTTPS
   ▼
Render Free ──► Django REST API (gunicorn + WhiteNoise, Dockerfile)
   │                        │
   │                        │ ORM (psycopg3, sslmode=require)
   ▼                        ▼
Vercel Hobby ◄── out/    Supabase Free ──► PostgreSQL (session pooler)
 (SSG estático,
  rebuild vía Deploy Hook)
```

- API: **Render Free**, contenedor desde `backend/Dockerfile` (Blueprint
  `render.yaml`), health check en `/api/v1/health/`, spin-down tras 15 min sin
  tráfico (cold start ~30-60 s), límite 750 h/mes.
- BD: **Supabase Free** (session pooler `aws-<region>.pooler.supabase.com:5432`),
  `sslmode=require`; sin backups automáticos → `scripts/backup.sh`.
- Web: **Vercel Hobby**, `output: 'export'` (100% estático), `SITE_URL` para
  sitemap/robots, rebuild al publicar con `scripts/deploy-web.sh` (Deploy Hook).
- Android: APK release firmado de instalación directa; keystore local.
- Detalles operativos: [docs/deployment.md](deployment.md).

## Reglas generales

- La **API REST es el único contrato** entre componentes. Ningún cliente accede
  directamente a PostgreSQL.
- La lógica de negocio vive en el backend (p. ej. "un borrador nunca es visible
  en el endpoint público").
- Android y Web solo se comunican con el backend vía HTTP/JSON.

## Responsabilidades y fronteras

| Componente | Responsabilidad | Frontera |
|---|---|---|
| Android | Presentación y CRUD de publicaciones (login, listar, crear, editar, eliminar, detalle), estados de carga/éxito/error/vacío. | No accede a PostgreSQL. |
| Web | Presentación pública de solo lectura: listado y detalle de publicaciones publicadas. | Sin autenticación. No accede a PostgreSQL. |
| Django REST API | Autenticación, autorización, validación, serialización, lógica de negocio y persistencia vía ORM. | Única puerta de entrada a datos. |
| PostgreSQL | Persistencia e integridad de datos. | Solo accesible desde el backend. |

## Contrato de API

- Versionado por ruta: `/api/v1/`.
- **Dos namespaces de posts separados**: privado (autenticado, usado por
  Android) y público (`/public/posts/`, solo lectura, usado por la web). Esto
  evita filtrar borradores por un error de filtrado.
- El contrato se fija en [api.md](api.md) y cualquier cambio posterior se
  coordina con los clientes afectados.

## Autenticación

- JWT con `djangorestframework-simplejwt`: login/refresh, access de corta
  duración (15 min) y refresh rotado (7 días). Sin CSRF (header
  `Authorization: Bearer`).
- La web pública no usa autenticación.

## Estrategia visual

- Web y Android construyen la UI con componentes propios y tokens de diseño
  (colores pastel, formas redondeadas) centralizados.
- Los recursos de pixel art (LibreSprite) se incorporarán de forma aditiva a
  través de componentes/slots, sin reescribir la estructura.
