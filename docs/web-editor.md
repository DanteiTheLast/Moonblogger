# Editor web privado

## Arquitectura y sesión

El editor web usa la misma API privada y las mismas cuentas que Android. El
navegador habla únicamente con `/api/editor/*` para autenticación y operaciones
privadas; los Route Handlers de Next.js llaman a Django usando `API_BASE_URL`.
No hay acceso desde Next.js a PostgreSQL ni copia de la lógica de publicación.
Los bytes de multimedia se envían directamente a las URLs efímeras de Storage,
como hace Android; nunca se envía el JWT de Django a Storage.

- `GET /api/editor/session`: `{authenticated,ownerKey}`; verifica el token contra
  el catálogo privado de Django, renovándolo si hace falta.
- `POST /api/editor/session`: `{username,password}`; devuelve exclusivamente
  `{authenticated,ownerKey}` y guarda JWT en cookies HttpOnly.
- `DELETE /api/editor/session`: cierra la sesión de este navegador, caducando las
  cookies. No invalida sesiones de Android ni otros dispositivos.
- `ownerKey` es un hash de API/usuario obtenido después de autenticación real;
  sirve para separar las copias locales de diferentes cuentas. No es una
  credencial ni sustituye los permisos de Django.

Las cookies son `HttpOnly; SameSite=Strict; Path=/api/editor`, `Secure` en
producción, con vigencia tomada de cada `exp` emitido por Django (actualmente
15 minutos de access y 7 días de refresh). No se guardan tokens en localStorage,
respuestas JSON, props, URLs o registros. Todas las respuestas privadas y fetches
a Django llevan `no-store`; se rechazan redirects del servidor remoto.

Cada petición privada incluye `X-MoonBlogger-Editor-Owner`, que debe coincidir
con la cuenta que abrió el formulario. Se comprueba antes de operar y antes y
después de renovar; una pestaña antigua no puede guardar con las cookies de
otra cuenta. Django sigue validando la firma JWT y los permisos.

Las mutaciones exigen Origin exactamente igual al de la petición, el header
`X-MoonBlogger-Editor: 1` y Content-Type `application/json`; se bloquea
`Sec-Fetch-Site: cross-site`. El límite JSON es 256 KiB comprobado mientras se lee
el stream, incluso sin Content-Length. No hay proxy arbitrario: la lista de
métodos, rutas y parámetros es cerrada y se valida antes de llamar a Django.
Se exponen catálogo, listado/creación/detalle/PATCH de posts y las operaciones de
media existentes. No se ofrece borrar publicaciones.

## Renovación y errores

Django rota y bloquea los refresh tokens anteriores. El cliente serializa las
peticiones privadas. Cuando el navegador ofrece Web Locks, la serialización
incluye las demás pestañas del mismo origen; existe fallback por pestaña.
El BFF agrupa renovaciones concurrentes en su proceso y
reutiliza su resultado durante 15 segundos. Un 401 del access permite renovar y
repetir una vez; un fallo de red de una escritura nunca se reintenta a ciegas.
Errores transitorios conservan las cookies. Un refresh rechazado tampoco borra
cookies automáticamente: una respuesta concurrente exitosa puede haberlas
renovado. Se requiere volver a iniciar sesión si ya no se puede renovar.

La agrupación en memoria no es un bloqueo distribuido entre instancias Vercel.
Sin Web Locks, dos pestañas o instancias que renueven el mismo refresh a la vez
pueden recibir un fallo recuperable en una de ellas. La interfaz conserva la
edición local y la petición fallida se puede repetir después de la renovación de
la otra pestaña. Resolver esa carrera de manera global exigiría una sesión
central o una política de gracia de rotación en Django; no se incorpora otro
servicio para ello.

Los errores internos de red/servidor se convierten en mensajes genéricos sin
HTML ni secretos. Se conservan los errores de validación de campos de Django
para ayudar a corregir el formulario.

## Despliegue

No se añaden secretos ni servicios. Vercel necesita el `API_BASE_URL` HTTPS ya
configurado y el backend desplegado con sus rutas actuales. Las cookies están
ligadas al dominio del editor: iniciar sesión en un preview no autentica el
dominio de producción. En local puede usarse HTTP para pruebas; la compilación
de producción requiere una API HTTPS antes de transmitir credenciales.

Las nuevas rutas no alteran los endpoints públicos ni las cuentas existentes.
La ruta del editor debe llevar metadata `noindex,nofollow`, quedar fuera del
sitemap y del registro público de visitas. Las copias locales contienen título, texto, etiquetas, estado de publicación,
transición del carrusel e identificadores de la publicación y de sus archivos.
También guardan el orden, la portada, textos alternativos, pies de foto,
identificadores pendientes de retirada y puntos de recuperación del guardado o
la carga. Excluyen los bytes de archivos, contraseñas, tokens, URLs firmadas y
vistas previas temporales. Se separan por usuario/API y son una ayuda de
recuperación, no un almacenamiento permanente ni cifrado. La política de privacidad describe
las cookies esenciales y estas copias.

## Verificación

Las pruebas de `web/lib/editorServer.test.ts` cubren cookies seguras y su
caducidad, exclusión de tokens de JSON, comprobación real de sesión, CSRF,
limitación de payload, allowlist de rutas/parámetros, aislamiento de headers,
rotación/coalescing y conservación de credenciales ante fallos. La validación
completa de la UI y de las operaciones reales se documenta con la entrega.
