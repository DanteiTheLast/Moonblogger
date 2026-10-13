# Optimización de imágenes — 13/10/2026

## Android: preparar antes de subir

La app prepara una copia privada y mantiene intacto el archivo del teléfono. El original puede pesar hasta 32 MiB; el límite de subida sigue siendo 8 MiB por imagen. Esto permite seleccionar una foto de 15 MB y reducirla antes de crear el upload intent.

- Lado mayor máximo: 2560 píxeles, sin ampliar imágenes pequeñas.
- Fotografías y gráficos opacos: JPEG con calidad 88/100.
- Imágenes con píxeles transparentes: PNG, conservando alfa y sin compresión con pérdida del códec PNG. El redimensionamiento sí modifica los píxeles.
- Se aplica la orientación EXIF, incluidas variantes reflejadas, y se generan metadatos de tamaño/dimensiones del resultado real.
- Se recodifican los píxeles en sRGB y no se copian GPS ni otros metadatos del original. Esta política también recodifica imágenes pequeñas.
- Lectura por bloques, inspección de dimensiones, decodificación muestreada y límite de píxeles para evitar decodificar un original enorme completo.
- Si el resultado no puede prepararse de forma segura o no cabe en 8 MiB, se informa del fallo; no se sube silenciosamente el original ni se baja mucho la calidad. Imágenes animadas no se aplanan silenciosamente.

No cambia el contrato de API ni se procesa imagen alguna en Django. Las URLs firmadas y la confirmación de uploads funcionan con los bytes del archivo preparado.

## Web: descargar el tamaño apropiado

Portadas y carrusel usan `next/image` con calidad 88, WebP cuando el cliente lo admite y `sizes` adaptativos. Se generan tamaños hasta 1440 píxeles para los contenedores actuales; los pósters de vídeo usan 1280 píxeles. Se conservan proporciones, textos alternativos y fallbacks, sin descargar un original como sustitución silenciosa si falla el optimizador.

Sólo se permiten URLs HTTPS del proyecto Supabase y del bucket público concreto, sin query strings ni redirecciones. No se amplía el acceso al bucket privado ni a hosts arbitrarios.

La caché del optimizador tiene un mínimo de 60 segundos, que el `Cache-Control` del origen puede prolongar. Retirar una publicación o borrar su objeto no garantiza eliminar inmediatamente todas las copias de CDN. Las claves nuevas de promoción evitan reutilizar una URL con bytes distintos. Esta optimización usa la infraestructura y cuota de imágenes del despliegue Next.js/Vercel existente.

## Comprobación

La prueba HTTP del optimizador Next.js sobre un JPEG sintético de 18,9 MB produjo WebP de 121 KB a 640 píxeles y 653 KB a 1280 píxeles, con calidad 88. También se verificó que un host ajeno y el bucket privado se rechazan. Son resultados de una imagen concreta, no una garantía de peso para cualquier fotografía.

Los archivos ya almacenados no se borran, reemplazan ni migran. Para aplicar la preparación antes de subir es necesario instalar la nueva versión Android; actualizar solamente la web no modifica el comportamiento del APK anterior.

Las pruebas con códecs Android nativos (Robolectric) prepararon un PNG opaco
sintético de 27.514.663 bytes, 4000 × 2000 píxeles, como JPEG de 2.191.452
bytes, 2560 × 1280 píxeles, con calidad 88 (aproximadamente 92 % menos).
También cubren las ocho orientaciones EXIF, GPS omitido, canal alfa,
no ampliación, rechazo de animación y limpieza tras fallo. No se ha medido aún
el rendimiento en un teléfono físico.
