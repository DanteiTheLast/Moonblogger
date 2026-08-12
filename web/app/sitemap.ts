import type { MetadataRoute } from "next";
import { getPublishedPosts } from "@/lib/api";

/**
 * URL base pública del sitio. Se lee de `SITE_URL` en tiempo de build
 * (server-side) para generar el sitemap. NO es `NEXT_PUBLIC_`: no hay
 * fetching en el cliente en v1.
 */
function getSiteUrl(): string {
  return (process.env.SITE_URL ?? "http://localhost:3000").replace(/\/+$/, "");
}

/**
 * Sitemap estático generado en build (`out/sitemap.xml`).
 *
 * Con `output: 'export'`, Next pre-renderiza esta metadata route durante el
 * build y escribe el archivo `sitemap.xml` en la salida estática. La lista de
 * publicaciones se obtiene de la API pública en build, igual que las páginas.
 *
 * NOTA: se fuerza `dynamic = 'force-static'` para que Next la trate como
 * metadata route estática (sin route handler dinámico) y sea compatible con
 * `output: 'export'`.
 */
export const dynamic = "force-static";

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const baseUrl = getSiteUrl();

  const posts = await getPublishedPosts();

  const postEntries: MetadataRoute.Sitemap = posts.map((post) => ({
    url: `${baseUrl}/posts/${post.slug}`,
    lastModified: post.published_at ?? post.updated_at,
    changeFrequency: "daily",
    priority: 0.7,
  }));

  return [
    {
      url: baseUrl,
      lastModified: new Date(),
      changeFrequency: "daily",
      priority: 1,
    },
    ...postEntries,
  ];
}
