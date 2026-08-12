import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // SSG puro: Next genera una carpeta estática `out/` que Vercel sirve
  // directamente (sin funciones serverless). Todo se pre-renderiza en build.
  output: "export",

  // Con `output: 'export'` no hay Image Optimization en producción (no hay
  // servidor Next), así que las imágenes se sirven tal cual. Se deja
  // configurado por si más adelante se usan imágenes con `next/image`.
  images: {
    unoptimized: true,
  },

  // Sin trailing slash (decisión del coordinador): con export, Next 16 genera
  // `out/posts/<slug>.html` para las rutas dinámicas, y Vercel sirve el URL
  // `/posts/<slug>` sin barra final mapeando a ese archivo.
  // Por tanto `trailingSlash` no es necesario para rutas [slug].
};

export default nextConfig;
