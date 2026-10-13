"use client";
import Image from "next/image";
import { IMAGE_QUALITY, THUMBNAIL_SIZES } from "@/lib/images";
import { useState } from "react";
import styles from "./PostCard.module.css";

export default function PostThumbnail({ url, alt }: {
  url: string | null | undefined;
  alt: string;
  width?: number | null;
  height?: number | null;
}) {
  const [failedUrl, setFailedUrl] = useState<string>();
  if (!url || failedUrl === url) return <div className={styles.thumbnailFallback} aria-hidden="true" />;
  return <Image className={styles.thumbnailImage} src={url} alt={alt}
    fill sizes={THUMBNAIL_SIZES} quality={IMAGE_QUALITY} loading="lazy"
    onError={() => setFailedUrl(url)} />;
}
