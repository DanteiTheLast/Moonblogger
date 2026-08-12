# MoonBlogger — Frontend web

Frontend público de solo lectura de MoonBlogger: muestra las publicaciones de
Moon que el backend ha marcado como `published`.

- **Stack:** Next.js + TypeScript con App Router.
- **Rendering:** pre-renderizado estático (SSG) durante `npm run build`. No hay
  fetching en el cliente en v1 (no se usan variables `NEXT_PUBLIC_`).
- **Estilos:** CSS Modules + `app/tokens.css` (tokens de diseño pastel).
- **API:** se consume la API pública de Django (`/api/v1/public/posts/`), solo
  lectura. Ver `docs/api.md` en la raíz del repositorio.

## Requisitos

- Node 22 (probado con v22.23.2).
- Backend de Django local corriendo en `http://127.0.0.1:8000`. La API se
  consulta **durante el build** (SSG), por lo que el backend debe estar arriba
  para generar contenido.
- `API_BASE_URL`: URL base de la API (sin barra final). Se lee como variable de
  entorno en build; default `http://127.0.0.1:8000/api/v1`. Puedes copiar
  `.env.example` a `.env.local` para fijarla localmente.

## Comandos

```bash
npm run dev     # Servidor de desarrollo (consulta la API en cada request)
npm run build   # Build de producción (SSG): limpia la caché de fetch y
                # pre-renderiza las publicaciones publicadas
npm start       # Sirve el build de producción
npm test        # Tests unitarios (Vitest, lib/api)
npm run lint    # ESLint
```

Nota sobre `npm run build`: el script limpia la caché de fetch de Next
(`.next/cache/fetch-cache`) antes de compilar, de modo que cada build obtiene
contenido fresco de la API aunque `.next` ya exista. Si una publicación cambia
de estado o se crea una nueva, basta con reconstruir.

## Rutas

- `/` — listado de publicaciones publicadas.
- `/posts/[slug]` — detalle de una publicación por slug (SSG).
- Los slugs no publicados (borradores o inexistentes) responden **404**, tanto
  en `npm start` como en producción. Un despliegue 100% estático con
  `output: 'export'` queda para la etapa de despliegue.
