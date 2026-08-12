/**
 * Tipos alineados 1:1 con el contrato del objeto Post de la API.
 * Ver docs/api.md (objeto Post) y backend/posts/serializers.py.
 */

export type PostStatus = "draft" | "published";

export interface Post {
  id: number;
  slug: string;
  title: string;
  content: string;
  status: PostStatus;
  created_at: string;
  updated_at: string;
  published_at: string | null;
}

/** Envelope de paginación de DRF (PageNumberPagination). */
export interface PostListResponse {
  count: number;
  next: string | null;
  previous: string | null;
  results: Post[];
}
