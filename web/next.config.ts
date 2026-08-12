import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // ISR (Incremental Static Regeneration) con revalidación por tags.
  // Next genera páginas estáticas en build y las revalida bajo demanda
  // vía webhook (revalidateTag) o por tiempo (revalidate: 3600).
  //
  // Sin `output: 'export'`, Next usa funciones serverless en Vercel
  // para servir ISR y API routes.
  images: {
    // Con ISR en Vercel, Image Optimization está disponible.
    // Se mantiene `unoptimized: false` (valor por defecto).
  },
};

export default nextConfig;
