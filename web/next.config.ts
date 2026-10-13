import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // ISR (Incremental Static Regeneration) con revalidación por tags.
  // Next genera páginas estáticas en build y las revalida bajo demanda
  // vía webhook (revalidateTag) o por tiempo (revalidate: 3600).
  //
  // Sin `output: 'export'`, Next usa funciones serverless en Vercel
  // para servir ISR y API routes.
  images: {
    // Solo archivos del bucket público del proyecto, nunca uploads privados.
    remotePatterns: [{
      protocol: "https",
      hostname: "zubconvayinjmnbqqxla.supabase.co",
      port: "",
      pathname: "/storage/v1/object/public/moonblogger-public/**",
      search: "",
    }],
    qualities: [88],
    deviceSizes: [320, 640, 750, 1080, 1280, 1440],
    formats: ["image/webp"],
    maximumRedirects: 0,
    // El Cache-Control de origen puede prolongar este mínimo.
    minimumCacheTTL: 60,
  },
};

export default nextConfig;
