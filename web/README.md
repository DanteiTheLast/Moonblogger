# MoonBlogger — Frontend web

Frontend público de solo lectura de MoonBlogger: muestra las publicaciones de
Moon que el backend ha marcado como `published`.

- **Stack:** Next.js + TypeScript con App Router.
- **Rendering:** ISR con revalidación temporal (1 h) y bajo demanda mediante
  webhook. No hay fetching en el cliente en v1 (no se usan variables
  `NEXT_PUBLIC_`).
- **Estilos:** CSS Modules + `app/tokens.css` (tokens de diseño pastel).
- **API:** se consume la API pública de Django (`/api/v1/public/posts/`), solo
  lectura. Ver `docs/api.md` en la raíz del repositorio.

## Requisitos

- Node 22 (probado con v22.23.2).
- Backend de Django local en `http://127.0.0.1:8000` para ver contenido. Si la
  API no responde durante el build, este no falla: las páginas se regeneran al
  estar disponible la API o al recibir el webhook.
- `API_BASE_URL`: URL base de la API (sin barra final). Se lee como variable de
  entorno en build; default `http://127.0.0.1:8000/api/v1`. Puedes copiar
  `.env.example` a `.env.local` para fijarla localmente.
- `SITE_URL`: URL pública del sitio (sin barra final), usada para
  `sitemap.xml` y `robots.txt`; default `http://localhost:3000`.
- `REVALIDATE_SECRET`: secreto compartido con `WEB_REVALIDATE_SECRET` de
  Render. La API envía su SHA-256 al webhook interno.

## Comandos

```bash
npm run dev     # Servidor de desarrollo (consulta la API en cada request)
npm run build   # Build de producción con ISR
npm run start   # Sirve el build de producción localmente
npm test        # Tests unitarios (Vitest, lib/api)
npm run lint    # ESLint
```

Nota sobre `npm run build`: el script limpia la caché de fetch de Next
(`.next/cache/fetch-cache`) antes de compilar para obtener contenido fresco
cuando la API está disponible. En producción, ISR actualiza el contenido por
tiempo o por webhook; no es necesario reconstruir la web al publicar.

La aplicación expone además las rutas de SEO:

- `/sitemap.xml` — sitemap con la home y cada publicación publicada.
- `/robots.txt` — permite el rastreo y referencia al sitemap.

## Despliegue (Vercel)

La web se despliega en Vercel (plan Hobby) con ISR: Vercel ejecuta `npm run
build`, sirve las páginas cacheadas y ejecuta la ruta interna de revalidación.

Pasos una sola vez:

1. Conectar el repositorio en Vercel → **Add New → Project**.
2. **Root Directory:** `web` (el monorepo también contiene `backend/` y `android/`).
3. En **Environment Variables**:
   - `API_BASE_URL` → URL real de la API desplegada en Render (p. ej.
     `https://<app>.onrender.com/api/v1`, sin barra final).
   - `SITE_URL` → URL pública del sitio (p. ej. `https://<proyecto>.vercel.app`).
   - `REVALIDATE_SECRET` → string aleatorio seguro, igual que
     `WEB_REVALIDATE_SECRET` en Render.
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
2. Tras confirmar la transacción, Render manda un webhook firmado a
   `/api/revalidate` en Vercel.
3. Vercel invalida el tag `posts`; la siguiente petición sirve contenido fresco.
   Un push a GitHub solo es necesario para cambios de código.

## Rutas

- `/` — listado de publicaciones publicadas.
- `/posts/[slug]` — detalle de una publicación por slug con ISR.
- `/sitemap.xml` — sitemap generado por una metadata route.
- `/robots.txt` — reglas de rastreo generadas por una metadata route.
- Los slugs no publicados (borradores o inexistentes) responden **404** desde
  la API pública y se muestran con `not-found.tsx`.
