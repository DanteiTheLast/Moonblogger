/* eslint-disable @next/next/no-img-element -- Supabase no tiene host configurado para next/image. */
import Link from "next/link";
import type { PostListItem } from "@/lib/types";
import { formatDate } from "@/lib/format";
import styles from "./PostCard.module.css";
import KotoSprite from "./KotoSprite";

const EXCERPT_LENGTH = 220;

interface PostCardProps {
  post: PostListItem;
}

/** Tarjeta de una publicación en el listado. */
export default function PostCard({ post }: PostCardProps) {
  const date = post.published_at ?? post.created_at;
  const isTruncated = post.content.length > EXCERPT_LENGTH;
  const excerpt = post.content.slice(0, EXCERPT_LENGTH);
  const cover = post.cover;
  const coverUrl = cover?.kind === "video" ? cover.poster_url : cover?.url;
  const coverAlt =
    cover?.alt_text ||
    (cover?.kind === "video"
      ? `Vista previa del vídeo de ${post.title}`
      : `Portada de ${post.title}`);

  return (
    <li className={styles.item}>
      <article className={styles.card}>
        {coverUrl ? (
          // Se usa <img> nativo: las URLs públicas de Supabase no requieren ni
          // deben añadirse a la configuración de hosts de next/image.
          <img
            className={styles.thumbnailImage}
            src={coverUrl}
            alt={coverAlt}
            width={cover?.width ?? undefined}
            height={cover?.height ?? undefined}
            loading="lazy"
          />
        ) : (
          <div className={styles.thumbnailFallback}>
            <KotoSprite variant="sleeping" size="md" />
            <span>Sin portada disponible</span>
          </div>
        )}
        <div className={styles.body}>
          <h2 className={styles.title}>
            <Link href={`/posts/${post.slug}`}>{post.title}</Link>
          </h2>
          <time className={styles.date} dateTime={date}>
            {formatDate(date)}
          </time>
          <p className={styles.excerpt}>
            {excerpt}
            {isTruncated ? "…" : ""}
          </p>
        </div>
      </article>
    </li>
  );
}
