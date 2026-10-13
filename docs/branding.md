# Identidad gráfica de MoonBlogger — 13/10/2026

Se usa el PNG `Cozy Pixel Cat Moonlight.png` aportado por el propietario para el
icono de Android y los iconos web (favicon, Apple y tamaños 192/512). Los recursos
se escalan manteniendo toda la composición; el launcher adaptativo incorpora
margen para que la máscara no corte al gato ni a la luna.

El WebP `koto_moonblogger_animado.webp` se conserva sin modificar: 512 × 512,
6 fotogramas, ciclo de 2300 ms (450, 350, 350, 350, 350 y 450 ms). Sustituye el
gráfico del recuadro del encabezado y la ilustración de portada, manteniendo los
bordes y sombras. No pasa por el optimizador de imágenes, para no aplanar la
animación. Con `prefers-reduced-motion`, el navegador selecciona el PNG estático.
Las ilustraciones de error, vacío y página no encontrada se conservan.

Android muestra la animación en login y en la inicialización de sesión. Coil
reproduce el WebP original en Android 9/API 28 o posterior; Android 8/API 26–27
usa seis fotogramas extraídos, con los píxeles y tiempos originales. El fallback
se pausa al dejar de estar activa la pantalla. Cuando la escala de animaciones
del sistema está desactivada se muestra el PNG.

El splash nativo de Android 12/API 31 usa una imagen estática: el formato de
animación admitido por esa pantalla del sistema no es WebP. Después, la pantalla
de carga Compose muestra el gato animado durante la inicialización real. No se
introduce una espera artificial ni se cambia el flujo de autenticación. Una
sesión que se inicialice muy rápido puede pasar al siguiente flujo antes de
mostrar un ciclo completo.

Los originales de Downloads no se modifican. Se requiere instalar el nuevo APK
para que la identidad gráfica cambie en la aplicación móvil.
