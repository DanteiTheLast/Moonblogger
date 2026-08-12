import Link from "next/link";
import type { Post } from "@/lib/types";
import { formatDate } from "@/lib/format";
import styles from "./PostCard.module.css";

const EXCERPT_LENGTH = 220;

interface PostCardProps {
  post: Post;
}

/** Tarjeta de una publicación en el listado. */
export default function PostCard({ post }: PostCardProps) {
  const date = post.published_at ?? post.created_at;
  const isTruncated = post.content.length > EXCERPT_LENGTH;
  const excerpt = post.content.slice(0, EXCERPT_LENGTH);

  return (
    <li className={styles.item}>
      <article className={styles.card}>
        {/* Slot para miniatura (pixel art futuro). Vacío hoy: reserva el espacio. */}
        <div className={styles.thumbnailSlot} aria-hidden="true" />
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
