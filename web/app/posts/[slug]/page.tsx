import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError, getPublishedPostBySlug, getPublishedPosts } from "@/lib/api";
import { formatDate } from "@/lib/format";
import styles from "./page.module.css";

interface PostPageProps {
  params: Promise<{ slug: string }>;
}

/**
 * SSG estricto: solo se sirven los slugs generados en build.
 * Cualquier otro slug (borrador, inexistente) responde 404 sin
 * consultar la API en tiempo de request.
 */
export const dynamicParams = false;

/** Pre-renderiza cada publicación publicada (SSG). */
export async function generateStaticParams() {
  const posts = await getPublishedPosts();
  return posts.map((post) => ({ slug: post.slug }));
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
      {/* Contenido en texto plano: se respetan los saltos de línea (pre-wrap). */}
      <div className={styles.content}>{post.content}</div>
      <Link href="/" className={styles.back}>
        Volver al listado
      </Link>
    </article>
  );
}
