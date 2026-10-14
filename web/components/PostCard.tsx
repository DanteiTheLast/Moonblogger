import Link from "next/link";
import Hashtags from "./Hashtags";
import PostThumbnail from "./PostThumbnail";
import type { PostListItem } from "@/lib/types";
import { formatDate } from "@/lib/format";
import styles from "./PostCard.module.css";

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
  const coverAlt = cover?.alt_text ?? "";

  return (
    <li className={styles.item}>
      <article className={styles.card}>
        <div className={styles.cardLink}>
          <div className={styles.thumbnailFrame}>
            <PostThumbnail url={coverUrl} alt={coverAlt} width={cover?.width} height={cover?.height} />
          </div>
          <div className={styles.body}>
            <h2 className={styles.title}><Link className={styles.titleLink} href={`/posts/${post.slug}`}><span className={styles.readLabel}>Leer publicación: </span>{post.title}</Link></h2>
            <time className={styles.date} dateTime={date}>
              {formatDate(date)}
            </time>
            <Hashtags tags={post.tags} />
            <p className={styles.excerpt}>
              {excerpt}
              {isTruncated ? "…" : ""}
            </p>
            <span className={styles.cta} aria-hidden="true">Leer publicación</span>
          </div>
        </div>
      </article>
    </li>
  );
}
