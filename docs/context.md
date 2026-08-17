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
- Desarrollo local primero; despliegue a producción como etapa final separada.

## Fuera de alcance de la v1

Comentarios, likes, categorías, social login, imágenes en publicaciones,
multi-usuario, edición desde la web.

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
