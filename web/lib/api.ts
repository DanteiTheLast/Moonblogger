import type { Post, PostListItem, PostListResponse } from "./types";

import { unstable_cache } from "next/cache";

export const DEFAULT_API_BASE_URL = "http://127.0.0.1:8000/api/v1";

// Un intento amplio cubre el arranque en frío; el segundo comparte el presupuesto.
const REQUEST_BUDGET_MS = 75_000;
const REQUEST_TIMEOUT_MS = 65_000;
const MAX_REQUEST_ATTEMPTS = 2;
const RETRY_DELAY_MS = 1_000;

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

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Solo se reintenta lo que puede deberse al arranque de la API. */
function isRetryable(error: ApiError): boolean {
  return error.status === 0 || error.status >= 500;
}

async function fetchOnce<T>(baseUrl: string, path: string, timeout: number): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      headers: { Accept: "application/json" },
      cache: "no-store",
      signal: AbortSignal.timeout(timeout),
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

  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError("La API devolvió una respuesta inválida.", 502);
  }
}

async function requestUncached<T>(baseUrl: string, path: string, deadline = Date.now() + REQUEST_BUDGET_MS): Promise<T> {
  let lastError: ApiError | null = null;

  for (let attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt += 1) {
    try {
      return await fetchOnce<T>(baseUrl, path, Math.max(1, Math.min(REQUEST_TIMEOUT_MS, deadline - Date.now())));
    } catch (error) {
      // Los errores no reintentables (p. ej. 404) se lanzan de inmediato.
      if (!(error instanceof ApiError) || !isRetryable(error)) {
        throw error;
      }
      lastError = error;
      if (attempt < MAX_REQUEST_ATTEMPTS && deadline - Date.now() > RETRY_DELAY_MS) {
        await delay(RETRY_DELAY_MS);
      } else {
        break;
      }
    }
  }

  throw lastError;
}

// El caché recibe sólo respuestas completas y exitosas. Un error de regeneración
// conserva el resultado anterior. El webhook vence el caché de datos de inmediato.
const cachedRequest = unstable_cache(
  (baseUrl: string, path: string) => requestUncached<unknown>(baseUrl, path),
  ["moon-public-api-v2"],
  { revalidate: 3600, tags: ["posts"] },
);

async function request<T>(path: string): Promise<T> {
  return await cachedRequest(getApiBaseUrl(), path) as T;
}

export async function getPublishedPostsPage(page = 1): Promise<PostListResponse> {
  if (!Number.isSafeInteger(page) || page < 1) throw new ApiError("Página inválida.", 400);
  return request<PostListResponse>(`/public/posts/${page === 1 ? "" : `?page=${page}`}`);
}

/** Compatibilidad con consumidores de la primera página. */
export async function getPublishedPosts(): Promise<PostListItem[]> {
  return (await getPublishedPostsPage()).results;
}

// El archivo se cachea como unidad: una página fallida nunca publica un sitemap parcial.
export const getAllPublishedPosts = unstable_cache(
  async (baseUrl: string): Promise<PostListItem[]> => {
    const deadline = Date.now() + REQUEST_BUDGET_MS;
    const posts: PostListItem[] = [];
    let page = 1;
    while (true) {
      if (Date.now() >= deadline) throw new ApiError("Se agotó el tiempo para cargar el archivo.", 0);
      const data = await requestUncached<PostListResponse>(baseUrl, `/public/posts/${page === 1 ? "" : `?page=${page}`}`, deadline);
      posts.push(...data.results);
      if (!data.next) return posts;
      if (!data.results.length || page >= 2500) throw new ApiError("Paginación del archivo inválida o demasiado grande.", 502);
      page += 1;
    }
  },
  ["moon-public-archive-v2"],
  { revalidate: 3600, tags: ["posts"] },
);

/** Detalle público por slug. 404 si es borrador o no existe. */
export async function getPublishedPostBySlug(slug: string): Promise<Post> {
  return request<Post>(`/public/posts/${encodeURIComponent(slug)}/`);
}
