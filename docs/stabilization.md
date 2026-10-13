# Correcciones de estabilización — 2026-10-13

## Alcance

Correcciones derivadas de la auditoría del backend Django, web Next.js y cliente Android. No se modifican los datos editoriales ni se crean migraciones de esquema. El despliegue requiere un commit y push autorizados; los cambios locales y sus pruebas no significan que Vercel o Render ya los ejecuten.

## Backend y base de datos

- Publicaciones usan rutas nuevas para cada promoción de medios; la limpieza diferida no elimina objetos reutilizados por otra publicación.
- Reintentos persistentes y cancelación de tareas de eliminación cuando el objeto sigue referenciado; llamadas a Storage fuera de transacciones.
- Bloqueos coherentes Post → Media para impedir que un PATCH concurrente restaure medios retirados.
- Límites de login/refresh, errores de Storage controlados, fecha original de publicación conservada y slugs limitados.
- Administración de medios y tareas de limpieza de sólo lectura.
- `/api/v1/ready/` verifica conexión SQL; Render usa esta ruta. `/health/` conserva su función de liveness. Readiness no demuestra que todas las tablas o Storage funcionen.
- `backup_media_storage` descarga los objetos conocidos por el ORM con manifiesto SHA256 y permisos privados. Coordinar una ventana sin escrituras y un respaldo PostgreSQL. No se ha ejecutado un respaldo completo ni un ensayo de restauración.
- `secure_data_api` previsualiza o revoca permisos de PUBLIC/anon/authenticated sobre tablas Django y secuencias dependientes. `--apply --plan-file /ruta/privada/nueva.json` conserva ACL y SQL de rollback fuera de Git; aborta si detecta permisos heredados residuales, ACL de columnas o cambios de acceso del propietario/service_role. Repetir la comprobación después de migraciones: no altera defaults globales, RLS ni Storage.

## Web

- Errores de API se propagan a la regeneración y compilación; no se guarda una página de error como respuesta exitosa.
- Caché etiquetada de resultados correctos; paginación `/archive/[page]`, sitemap completo y presupuesto total acotado para llamadas a la API.
- Navegación, textos largos, miniaturas fallidas y controles de vídeo corregidos.
- Dependencias de producción actualizadas: auditoría npm de producción sin vulnerabilidades. Persisten cinco alertas altas en herramientas de desarrollo sin actualización compatible disponible durante la revisión.
- La prueba local de ISR confirmó respuestas de error tras invalidación con API caída, recuperación y ausencia del contenido retirado. El detalle retirado puede responder HTTP 200 por streaming con `noindex`; no se promete HTTP 404 en todos los casos ni retirada instantánea de cada caché CDN.

## Android

- Errores transitorios al refrescar tokens preservan la sesión; respuestas 401 la invalidan, con protección frente a cambios de sesión concurrentes.
- Borrador privado persistente por usuario/API/publicación, recuperación explícita, protección al salir y prevención de envíos dobles.
- Se conservan IDs de medios confirmados y checkpoints; archivos pendientes sin confirmación deben seleccionarse de nuevo. Un borrador corrupto requiere descarte explícito.
- Fallos de carga bloquean edición y permiten reintentar; paginación conserva resultados y presenta errores visibles.
- El PATCH preparatorio omite status para evitar retirar una publicación si se perdió la respuesta del envío final.
- Sigue pendiente la idempotencia de creación cuando se pierde la respuesta del primer POST. También faltan pruebas físicas de TalkBack, rendimiento del guardado y captura/subida de medios.

## Verificación

- Backend: 108 pruebas aprobadas sobre PostgreSQL 16 desechable, incluidas ocho pruebas de permisos, rollback y preservación de acceso.
- Web: 42 pruebas aprobadas, lint y compilación Next.js aprobados contra API de pruebas; comprobaciones de ISR y reflujo a 320/390/1280 px.
- Android: 54 pruebas JVM aprobadas; lintDebug sin errores (34 advertencias existentes) y assembleDebug aprobado. No se instaló en dispositivo ni se firmó release.
- CI agrega backend/PostgreSQL, web y Android. Su ejecución en GitHub queda pendiente del push.

## Estado comprobado de producción

Supabase fue reactivado por el propietario. Se aplicó la revocación de permisos públicos sobre 17 tablas y 14 secuencias; el comando verificó que no quedan permisos efectivos de los roles Data API y conservó los del propietario y service_role. Una comprobación posterior de sólo lectura confirmó conexión disponible y ausencia de grants anon/authenticated sobre las tablas principales. No hay vistas/materialized views ni funciones SECURITY DEFINER en el esquema public durante esta revisión. RLS permanece sin cambios; la protección aplicada es mediante ACL.

El plan y SQL de rollback están en un archivo privado fuera del repositorio: `../MoonBlogger-security-backups/acl-20261013-apply.json`. No contiene contraseñas.

La API pública respondió HTTP 200 con cuatro publicaciones después del cambio. La portada de Vercel también respondió HTTP 200, con cuatro enlaces a publicaciones y sin el error anterior. Esto confirma recuperación del servicio existente, no despliegue de estas correcciones: no se creó commit ni se hizo push.

## Operación pendiente

Revisar el cron real y su configuración privada; despertar Render no reactiva un proyecto Supabase pausado. Mantener tareas de limpieza periódicas, respaldos de PostgreSQL y Storage fuera del servidor, y verificar una restauración. El TTL de intents y URLs firmadas requiere una revisión adicional para cargas tardías y posibles objetos huérfanos.
