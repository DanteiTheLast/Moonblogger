/**
 * Tipos alineados 1:1 con el contrato del objeto Post de la API.
 * Ver docs/api.md (objeto Post) y backend/posts/serializers.py.
 */

export type PostStatus = "draft" | "published";
export type CarouselTransition = "slide" | "fade" | "bubble" | "none";
export type PostMediaKind = "image" | "video";

/** Elemento multimedia público, disponible únicamente en el detalle del post. */
export interface PostMedia {
  id: string;
  kind: PostMediaKind;
  position: number;
  is_cover: boolean;
  mime_type: string;
  width: number | null;
  height: number | null;
  duration_seconds: number | null;
  alt_text: string;
  caption: string;
  url: string | null;
  poster_url: string | null;
}

export interface PostBase {
  id: number;
  slug: string;
  title: string;
  content: string;
  status: PostStatus;
  created_at: string;
  updated_at: string;
  published_at: string | null;
}

/** Forma que devuelve el listado público paginado. */
export interface PostListItem extends PostBase {
  carousel_transition: CarouselTransition;
  cover: PostMedia | null;
  media_count: number;
}

/** Forma que devuelve el detalle público por slug. */
export interface Post extends PostListItem {
  media: PostMedia[];
}

/** Envelope de paginación de DRF (PageNumberPagination). */
export interface PostListResponse {
  count: number;
  next: string | null;
  previous: string | null;
  results: PostListItem[];
}
