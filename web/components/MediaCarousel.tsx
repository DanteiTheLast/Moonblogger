"use client";

/* eslint-disable @next/next/no-img-element -- Supabase no tiene host configurado para next/image. */
import { useEffect, useMemo, useRef, useState } from "react";
import type { CarouselTransition, PostMedia } from "@/lib/types";
import {
  getMediaAspectRatio,
  getSlideIndex,
  normalizeCarouselTransition,
  orderMedia,
} from "./mediaCarousel.logic";
import styles from "./MediaCarousel.module.css";

interface MediaCarouselProps {
  media: readonly PostMedia[];
  transition: CarouselTransition;
}

/** Carrusel accesible y sin dependencias para la galería pública de un post. */
export default function MediaCarousel({ media, transition }: MediaCarouselProps) {
  const items = useMemo(() => orderMedia(media), [media]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [failedMediaIds, setFailedMediaIds] = useState<ReadonlySet<string>>(
    () => new Set(),
  );
  const activeVideoRef = useRef<HTMLVideoElement | null>(null);
  const activeIndex = Math.min(selectedIndex, Math.max(items.length - 1, 0));
  const activeItem = items[activeIndex];
  const selectedTransition = normalizeCarouselTransition(transition);

  useEffect(() => {
    activeVideoRef.current?.pause();
  }, [activeIndex]);

  if (!activeItem) return null;

  function markMediaAsFailed(id: string) {
    setFailedMediaIds((previous) => new Set(previous).add(id));
  }

  function selectSlide(index: number) {
    activeVideoRef.current?.pause();
    setSelectedIndex(index);
  }

  function moveSlide(direction: -1 | 1) {
    selectSlide(getSlideIndex(activeIndex, items.length, direction));
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLElement>) {
    if (event.key === "ArrowLeft") {
      event.preventDefault();
      moveSlide(-1);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      moveSlide(1);
    } else if (event.key === "Home") {
      event.preventDefault();
      selectSlide(0);
    } else if (event.key === "End") {
      event.preventDefault();
      selectSlide(items.length - 1);
    }
  }

  const mediaUrl = activeItem.url ?? undefined;
  const isUnavailable = failedMediaIds.has(activeItem.id) || !mediaUrl;
  const accessibleName =
    activeItem.alt_text ||
    (activeItem.kind === "video"
      ? "Vídeo de la publicación"
      : "Imagen de la publicación");

  return (
    <section
      className={styles.carousel}
      aria-roledescription="carrusel"
      aria-label="Galería multimedia"
      aria-keyshortcuts="ArrowLeft ArrowRight Home End"
      tabIndex={0}
      onKeyDown={handleKeyDown}
    >
      <p className={styles.status} aria-live="polite">
        Elemento {activeIndex + 1} de {items.length}
      </p>
      <figure className={styles.figure}>
        <div
          key={activeItem.id}
          className={`${styles.mediaFrame} ${styles[selectedTransition]}`}
          style={{ aspectRatio: getMediaAspectRatio(activeItem) }}
        >
          {isUnavailable ? (
            <div className={styles.mediaFallback} role="status">
              No se pudo cargar este {activeItem.kind === "video" ? "vídeo" : "recurso"}.
            </div>
          ) : activeItem.kind === "image" ? (
            // Las URLs son públicas de Supabase; <img> evita configurar hosts remotos en Next.
            <img
              className={styles.media}
              src={mediaUrl}
              alt={accessibleName}
              width={activeItem.width ?? undefined}
              height={activeItem.height ?? undefined}
              onError={() => markMediaAsFailed(activeItem.id)}
            />
          ) : (
            <video
              ref={activeVideoRef}
              className={styles.media}
              controls
              playsInline
              preload="metadata"
              poster={activeItem.poster_url ?? undefined}
              aria-label={accessibleName}
              onError={() => markMediaAsFailed(activeItem.id)}
            >
              <source src={mediaUrl} type={activeItem.mime_type} />
              Tu navegador no puede reproducir este vídeo.
            </video>
          )}
        </div>
        {activeItem.caption ? (
          <figcaption className={styles.caption}>{activeItem.caption}</figcaption>
        ) : null}
      </figure>

      {items.length > 1 ? (
        <div className={styles.controls} aria-label="Controles de la galería">
          <button
            className={styles.control}
            type="button"
            onClick={() => moveSlide(-1)}
            aria-label="Elemento anterior"
          >
            <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false">
              <path d="M9.75 3.25 5 8l4.75 4.75M5.5 8h6.25" />
            </svg>
          </button>
          <div className={styles.indicators} aria-label="Seleccionar elemento">
            {items.map((item, index) => (
              <button
                key={item.id}
                className={styles.indicator}
                type="button"
                onClick={() => selectSlide(index)}
                aria-label={`Mostrar elemento ${index + 1} de ${items.length}`}
                aria-current={index === activeIndex ? "true" : undefined}
              />
            ))}
          </div>
          <button
            className={styles.control}
            type="button"
            onClick={() => moveSlide(1)}
            aria-label="Elemento siguiente"
          >
            <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false">
              <path d="m6.25 3.25L11 8l-4.75 4.75M10.5 8H4.25" />
            </svg>
          </button>
        </div>
      ) : null}
    </section>
  );
}
