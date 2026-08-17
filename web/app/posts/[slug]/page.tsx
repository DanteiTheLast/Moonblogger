import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError, getPublishedPostBySlug, getPublishedPosts } from "@/lib/api";
import MediaCarousel from "@/components/MediaCarousel";
import { formatDate } from "@/lib/format";
import styles from "./page.module.css";

interface PostPageProps {
  params: Promise<{ slug: string }>;
}

/**
 * ISR: se pre-renderizan los slugs en build y se revalida bajo demanda
 * (revalidateTag) o por tiempo (revalidate: 3600).
 * Si la API falla en build, generateStaticParams devuelve [] para no
 * bloquear el deploy; las páginas se generarán on-demand.
 */
export const revalidate = 3600;

/** Pre-renderiza cada publicación publicada (ISR). */
export async function generateStaticParams() {
  try {
    const posts = await getPublishedPosts();
    return posts.map((post) => ({ slug: post.slug }));
  } catch {
    // Si la API no responde (cold start, caída temporal), no bloqueamos el build.
    // Las páginas se generarán on-demand al primer request.
    return [];
  }
}

export async function generateMetadata({
  params,
}: PostPageProps): Promise<Metadata> {
  const { slug } = await params;
  try {
    const post = await getPublishedPostBySlug(slug);
    return {
      title: post.title,
      description: post.content.slice(0, 160),
    };
  } catch {
    return { title: "Publicación no encontrada" };
  }
}

export default async function PostPage({ params }: PostPageProps) {
  const { slug } = await params;

  let post;
  try {
    post = await getPublishedPostBySlug(slug);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }

  const date = post.published_at ?? post.created_at;

  return (
    <article className={styles.post}>
      <header className={styles.header}>
        <h1 className={styles.title}>{post.title}</h1>
        <time className={styles.date} dateTime={date}>
          {formatDate(date)}
        </time>
      </header>
      {post.media.length > 0 ? (
        <MediaCarousel
          media={post.media}
          transition={post.carousel_transition}
        />
      ) : null}
      {/* Contenido en texto plano: se respetan los saltos de línea (pre-wrap). */}
      <div className={styles.content}>{post.content}</div>
      <Link href="/" className={styles.back}>
        Volver al listado
      </Link>
    </article>
  );
}
