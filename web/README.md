# MoonBlogger — Frontend web

Frontend público de solo lectura de MoonBlogger: muestra las publicaciones de
Moon que el backend ha marcado como `published`.

- **Stack:** Next.js + TypeScript con App Router.
- **Rendering:** 100% estático. `output: 'export'` genera la carpeta `out/`
  durante `npm run build` (SSG puro). No hay fetching en el cliente en v1 (no
  se usan variables `NEXT_PUBLIC_`).
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
- `SITE_URL`: URL pública del sitio (sin barra final). Se lee en build para
  generar `sitemap.xml` y `robots.txt`; default `http://localhost:3000`.

## Comandos

```bash
npm run dev     # Servidor de desarrollo (consulta la API en cada request)
npm run build   # Build de producción (SSG): limpia la caché de fetch,
                # pre-renderiza las publicaciones publicadas y genera out/
npm run start   # OJO: "next start" NO funciona con output: 'export'.
                # Para previsualizar el estático localmente: npx serve@latest out
npm test        # Tests unitarios (Vitest, lib/api)
npm run lint    # ESLint
```

Nota sobre `npm run build`: el script limpia la caché de fetch de Next
(`.next/cache/fetch-cache`) antes de compilar, de modo que cada build obtiene
contenido fresco de la API aunque `.next` ya exista. Si una publicación cambia
de estado o se crea una nueva, basta con reconstruir.

El build de producción genera además los archivos estáticos de SEO:

- `out/` — sitio estático completo.
- `out/sitemap.xml` — sitemap con la home y cada publicación publicada.
- `out/robots.txt` — permite el rastreo y referencia al sitemap.

## Despliegue (Vercel)

La web se despliega en Vercel (plan Hobby) como sitio estático: Vercel ejecuta
`npm run build`, recibe la carpeta `out/` y la sirve en el CDN.

Pasos una sola vez:

1. Conectar el repositorio en Vercel → **Add New → Project**.
2. **Root Directory:** `web` (el monorepo también contiene `backend/` y `android/`).
3. En **Environment Variables**:
   - `API_BASE_URL` → URL real de la API desplegada en Render (p. ej.
     `https://<app>.onrender.com/api/v1`, sin barra final).
   - `SITE_URL` → URL pública del sitio (p. ej. `https://<proyecto>.vercel.app`).
4. **Build Command:** dejar el por defecto de Next (usa `npm run build`, que ya
   limpia la caché de fetch, por lo que cada build de Vercel obtiene contenido
   fresco de la API).

> Nota sobre el cold start de Render Free: la API se duerme a los 15 min sin
> tráfico y tarda 30-60 s en arrancar. El cliente web usa un timeout de ~90 s
> y reintenta automáticamente, por lo que el build normalmente aguanta el
> arranque. Si aun así un build fallara, basta con re-ejecutarlo con el
> servicio ya caliente.

Flujo de publicación:

1. Moon publica/edita contenido en la API (backend).
2. Se dispara el rebuild: ejecutar `scripts/deploy-web.sh` con la variable
   `VERCEL_DEPLOY_HOOK` definida, o bien hacer push a GitHub (Vercel reconstruye
   automáticamente).
3. Vercel ejecuta el build, vuelve a consultar la API y sirve la web actualizada.

## Rutas

- `/` — listado de publicaciones publicadas.
- `/posts/[slug]` — detalle de una publicación por slug (SSG).
- `/sitemap.xml` — sitemap estático generado en build.
- `/robots.txt` — reglas de rastreo estáticas generadas en build.
- Los slugs no publicados (borradores o inexistentes) responden **404**: con
  `output: 'export'` y `dynamicParams = false`, solo se generan los slugs
  publicados, y cualquier otro path cae en el `not-found.tsx` estático.
