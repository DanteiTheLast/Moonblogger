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
 * Sitemap con ISR: se revalida cada hora (revalidate: 3600) y bajo demanda
 * vía revalidateTag('posts') cuando se publica/actualiza un post.
 *
 * Si la API no está disponible en build, devuelve solo la URL base para no
 * bloquear el deploy; las entradas de posts se añadirán on-demand.
 */
export const revalidate = 3600;

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const baseUrl = getSiteUrl();

  let posts: Awaited<ReturnType<typeof getPublishedPosts>> = [];
  try {
    posts = await getPublishedPosts();
  } catch {
    // Si la API no responde en build, no bloqueamos el deploy.
    // El sitemap se regenerará on-demand con revalidateTag.
  }

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
