import type { CarouselTransition, PostMedia } from "@/lib/types";

const TRANSITIONS: readonly CarouselTransition[] = [
  "slide",
  "fade",
  "bubble",
  "none",
];

/** Protege la UI ante una respuesta antigua o inválida sin inventar una animación. */
export function normalizeCarouselTransition(
  transition: string | null | undefined,
): CarouselTransition {
  return TRANSITIONS.includes(transition as CarouselTransition)
    ? (transition as CarouselTransition)
    : "none";
}

/** Obtiene el siguiente índice y permite recorrer el carrusel de forma circular. */
export function getSlideIndex(
  currentIndex: number,
  total: number,
  direction: -1 | 1,
): number {
  if (total <= 1) return 0;
  return (currentIndex + direction + total) % total;
}

/** Mantiene la presentación estable si la API recibe elementos en distinto orden. */
export function orderMedia(media: readonly PostMedia[]): PostMedia[] {
  return [...media].sort((first, second) => first.position - second.position);
}

/** Relación de aspecto segura para reservar espacio antes de cargar el recurso. */
export function getMediaAspectRatio(media: PostMedia): string {
  if (media.width && media.height && media.width > 0 && media.height > 0) {
    return `${media.width} / ${media.height}`;
  }
  return "16 / 9";
}
