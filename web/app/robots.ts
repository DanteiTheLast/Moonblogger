import type { MetadataRoute } from "next";

/**
 * URL base pública del sitio. Se lee de `SITE_URL` en tiempo de build
 * (server-side) para generar el `robots.txt`. NO es `NEXT_PUBLIC_`: no hay
 * fetching en el cliente en v1.
 */
function getSiteUrl(): string {
  return (process.env.SITE_URL ?? "http://localhost:3000").replace(/\/+$/, "");
}

/**
 * `robots.txt` se sirve como metadata route de Next.js.
 *
 * Se permite el rastreo completo y se referencia el sitemap. Igual que el
 * sitemap, se fuerza estático porque no depende del contenido de la API.
 */
export const dynamic = "force-static";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
    },
    sitemap: `${getSiteUrl()}/sitemap.xml`,
  };
}
