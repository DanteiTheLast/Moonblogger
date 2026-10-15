# MoonBlogger — Frontend web

Frontend de MoonBlogger con blog público de solo lectura y editor privado para
Moon. El blog muestra únicamente las publicaciones que Django ha marcado como
`published`; el editor permite escribir y editar con la misma cuenta de Android.

- **Stack:** Next.js + TypeScript con App Router.
- **Rendering:** ISR con revalidación temporal (1 h) y bajo demanda mediante
  webhook para el contenido público. Los filtros públicos y el editor consultan
  rutas del mismo origen desde el navegador; no se usan variables `NEXT_PUBLIC_`
  para credenciales ni configuración privada.
- **Estilos:** CSS Modules + `app/tokens.css` (tokens de diseño pastel).
- **API:** el blog consume la API pública de Django (`/api/v1/public/posts/`),
  solo lectura. El editor usa `/api/editor/*` como BFF de Next.js hacia la API
  privada existente de Django. Ver `docs/api.md` en la raíz del repositorio.

## Requisitos

- Node 22 (probado con v22.23.2).
- Backend de Django local en `http://127.0.0.1:8000` para ver contenido. Si la
  API no responde durante el build sin datos cacheados, el build falla. Esto evita
  desplegar una pantalla de error como una página válida cacheada. Calienta o
  recupera la API antes de reintentar el despliegue.
- `API_BASE_URL`: URL base de la API (sin barra final). Se lee como variable de
  entorno en el servidor; default `http://127.0.0.1:8000/api/v1`. Puedes copiar
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
> tráfico y puede tardar 30-60 s en arrancar. El cliente reserva 65 s para
> el primer intento y permite un segundo después de 1 s, dentro de un presupuesto
> total de 75 s por consulta (también para el archivo completo del sitemap).
> Configura un tiempo máximo de función compatible en el proveedor; si el límite
> es menor, mantén la API activa o cambia su alojamiento. Si aun así un build fallara, basta con re-ejecutarlo con el
> servicio ya caliente.

Flujo de publicación:

1. Moon publica/edita contenido en la API (backend).
2. Tras confirmar la transacción, Render manda un webhook firmado a
   `/api/revalidate` en Vercel.
3. Vercel invalida el tag `posts`; la siguiente petición sirve contenido fresco.
   Un push a GitHub solo es necesario para cambios de código.

## Editor privado

En `/editor`, Moon puede iniciar sesión con el mismo usuario y contraseña de
Android, consultar borradores y publicaciones, crear o editar, asignar etiquetas
y gestionar las fotografías. `/editor/new` abre un escrito nuevo y
`/editor/[id]` uno existente. El editor conserva el estilo y la organización del
editor Android; el blog público continúa siendo de solo lectura.

El despliegue reutiliza el `API_BASE_URL` existente, con HTTPS en producción.
No requiere secretos adicionales ni migraciones. Next.js guarda los JWT en
cookies esenciales HttpOnly, Secure en producción y SameSite Strict. Las
mutaciones verifican el origen y una cabecera específica; todas las rutas
privadas evitan caché e indexación. Las peticiones quedan ligadas a la cuenta
original de cada pestaña para impedir guardar con otra cuenta tras un cambio
de sesión. Ver [seguridad, recuperación y despliegue del editor](../docs/web-editor.md).

## Rutas

- `/` — primera página de publicaciones publicadas.
- `/archive/[page]` — páginas antiguas con ISR, generadas al visitarlas.
- `/posts/[slug]` — detalle de una publicación por slug con ISR.
- `/editor`, `/editor/new`, `/editor/[id]` — listado, creación y edición privados.
- `/api/editor/*` — sesión y proxy privado acotado hacia Django; nunca entrega JWT al cliente.
- `/sitemap.xml` — sitemap generado por una metadata route.
- `/robots.txt` — reglas de rastreo generadas por una metadata route.
- Los slugs no publicados (borradores o inexistentes) responden **404** desde
  la API pública y se muestran con `not-found.tsx`.

## Recuperación y caché

Sólo las respuestas exitosas se guardan en el caché de datos (tag `posts`, 1 h).
Home y sitemap propagan los fallos: ISR conserva el último resultado útil cuando
falla una regeneración temporal. El sitemap recorre todo el archivo y se guarda
como unidad, sin publicar resultados parciales. El botón del boundary ejecuta
`reset()` para volver a intentar el render. Una respuesta exitosa vacía se muestra
como un blog sin publicaciones, separada de los fallos de conexión.

El webhook mantiene `revalidateTag('posts', { expire: 0 })`: una retirada vence
el caché de datos inmediatamente. Una retirada con la API sana devuelve 404 en
la siguiente regeneración. La conservación de páginas anteriores de ISR durante
una caída requiere verificar también el caché de rutas del proveedor; no basta
con invalidar datos para prometer una retirada durante una caída completa.
El build limpia el caché local deliberadamente;
requiere una API sana. No constituye una recuperación de Render/Supabase.

Dependencias: Next y eslint-config-next 16.3.8 corrigen los avisos que afectan
16.3.0; sharp y source-map-js se actualizan dentro de sus rangos compatibles.
Verificar `npm audit --omit=dev` antes del despliegue.

La auditoría completa conserva cinco avisos altos en herramientas de lint
(`braces` → `micromatch` → `fast-glob` → plugin/config de Next), sin parche
compatible anunciado. La sugerencia automática rebaja Next ESLint a 14;
no se aplica. Vitest 4.1.11 corrige el aviso de su mocker. El entorno npm 10.9.8
falló al resolver ese parche (`edgesOut`); el lockfile se resolvió con npm 11
usado temporalmente, sin cambiar la instalación global de npm.

### Etiquetas públicas

La principal conserva filtros OR en parámetros repetidos (`/?tag=dan&tag=viajes&page=2`), ordenados y sin duplicados. El catálogo se obtiene de `GET /public/tags/` (sólo etiquetas usadas en publicaciones públicas); el total y la paginación provienen de Django, sin filtrar una página ya descargada. Las combinaciones filtradas llevan `noindex, follow` y canonical de la principal.

La selección es pendiente hasta pulsar «Aplicar filtros». Las consultas posteriores pasan por `/api/public-feed` y mantienen el listado confirmado durante carga o error; el botón «Reintentar» conserva el filtro solicitado. Atrás/Adelante restaura las selecciones de la URL. Parámetros malformados muestran un error y «Ver todas»; no se convierten silenciosamente en un listado completo. El catálogo requiere el backend actualizado: no existe un catálogo ficticio de respaldo para producción.

Hashtags de tarjetas y detalle enlazan a la principal. La tarjeta utiliza un único enlace de lectura extendido; los hashtags son enlaces hermanos independientes, accesibles con teclado, sin anidación. Los colores y geometría emplean los mismos tokens semánticos de la versión azul de amanecer/ciruela aprobada.
