"use client";
/* eslint-disable @next/next/no-img-element -- URLs públicas de Supabase sin hosts configurados. */
import { useState } from "react";
import styles from "./PostCard.module.css";

export default function PostThumbnail({ url, alt, width, height }: {
  url: string | null | undefined;
  alt: string;
  width?: number | null;
  height?: number | null;
}) {
  const [failedUrl, setFailedUrl] = useState<string>();
  if (!url || failedUrl === url) return <div className={styles.thumbnailFallback} aria-hidden="true" />;
  return <img className={styles.thumbnailImage} src={url} alt={alt}
    width={width ?? undefined} height={height ?? undefined} loading="lazy"
    onError={() => setFailedUrl(url)} />;
}
