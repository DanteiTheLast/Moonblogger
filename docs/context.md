# MoonBlogger — Contexto

## Qué es

MoonBlogger es una aplicación personal para Moon. Una sola usuaria gestiona sus
publicaciones desde una app Android; un sitio web público permite a cualquier
visitante leer las publicaciones que Moon haya publicado.

## Componentes

- **Android** (Kotlin): login y CRUD completo de publicaciones (crear, editar,
  eliminar, consultar). Único cliente con escritura.
- **Django REST API**: lógica de negocio, autenticación, validación y acceso a
  datos. Única puerta de entrada a la base de datos.
- **PostgreSQL**: persistencia. Solo accesible desde el backend.
- **Web** (Next.js, ISR): sitio público de solo lectura con las publicaciones
  publicadas.

## Alcance de la primera versión

- Una sola cuenta (Moon).
- Publicaciones con título, contenido en texto plano y estado
  `draft` / `published` (publicado/borrador).
- La web solo muestra publicaciones en estado `published`.
- Producción desplegada: API en Render, PostgreSQL y Storage en Supabase, y web
  Next.js en Vercel con ISR. Android se distribuye como APK release.
- Multimedia de imágenes operativa de extremo a extremo: Android selecciona,
  valida y carga al Storage privado mediante la API; al publicar se promueve al
  bucket público y queda disponible en la API y web públicas.

## Fuera de alcance de la v1

Comentarios, likes, categorías, social login, multi-usuario, edición desde la
web, transcodificación de vídeo y canciones. El contrato de backend contempla
vídeo MP4 con póster, pero su carga desde Android y su flujo completo no están
validados todavía.

## Estado de sesión (17/08/2026)

- Stack productivo: `https://moonblogger-api.onrender.com`, Supabase
  PostgreSQL/Storage, Vercel ISR y APK Android release 0.1.2 (`versionCode` 3).
- Flujo real verificado: post publicado `final-ending` (id 9), una imagen JPEG
  720×1280 y `media_count: 1`; la respuesta pública confirmó URL de portada,
  MIME y contador. Android usa Photo Picker, copia privada en `cacheDir` y
  valida firma JPEG/PNG/WebP y tamaño máximo de 8 MiB antes de iniciar la carga.
- No se debe afirmar que vídeo Android ni TUS funcionen: ambos siguen
  pendientes de validación E2E. El backend sí mantiene el contrato de MP4 y
  póster descrito en [API](api.md).
- Verificado tras el ajuste final de Storage: 61 tests de backend aprobados y
  flujo de producción anterior aprobado. Hitos relevantes en `main`:
  `ec40115`, `3361c28`, `a6a2cbf`, `f7bd700`, `20b34ff` y `5155171`.

## Estética

Cute, acogedora y personal: colores pastel, interfaz sencilla, botones
redondeados, poco ruido visual y elementos de pixel art. Los sprites se crean
en LibreSprite y se integran en una etapa posterior de pulido visual (aditiva,
sin reescribir).

## Documentación relacionada

- [Arquitectura](architecture.md)
- [Decisiones técnicas](decisions.md)
- [Contrato de API](api.md)
- [Base de datos](database.md)
- [Despliegue y operación](deployment.md)
