import type { Post, PostListResponse } from "./types";

/**
 * Cliente de solo lectura de la API pública de Django.
 *
 * - Solo se usa desde server components durante el build (SSG): no hay
 *   fetching en cliente en v1.
 * - `API_BASE_URL` se lee de la variable de entorno en tiempo de build.
 * - Las respuestas se cachean con `force-cache` para que Next pueda
 *   pre-renderizar estáticamente (sin ISR en v1).
 */

export const DEFAULT_API_BASE_URL = "http://127.0.0.1:8000/api/v1";

export function getApiBaseUrl(): string {
  return process.env.API_BASE_URL ?? DEFAULT_API_BASE_URL;
}

/** Error tipado de la capa de datos. `status` es el código HTTP, 0 si no hubo conexión. */
export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function request<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${getApiBaseUrl()}${path}`, {
      headers: { Accept: "application/json" },
      cache: "force-cache",
    });
  } catch {
    throw new ApiError("No se pudo conectar con el servidor de MoonBlogger.", 0);
  }

  if (!response.ok) {
    throw new ApiError(
      `La API respondió con estado ${response.status}.`,
      response.status,
    );
  }

  return (await response.json()) as T;
}

/** Listado público (solo publicadas). En v1 se consume la primera página. */
export async function getPublishedPosts(): Promise<Post[]> {
  const data = await request<PostListResponse>("/public/posts/");
  return data.results;
}

/** Detalle público por slug. Devuelve ApiError con status 404 si es borrador o no existe. */
export async function getPublishedPostBySlug(slug: string): Promise<Post> {
  return request<Post>(`/public/posts/${encodeURIComponent(slug)}/`);
}
