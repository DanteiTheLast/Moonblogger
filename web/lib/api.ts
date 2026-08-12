import type { Post, PostListResponse } from "./types";

/**
 * Cliente de solo lectura de la API pública de Django.
 *
 * - Se usa desde server components durante el build (ISR): no hay
 *   fetching en cliente en v1.
 * - `API_BASE_URL` se lee de la variable de entorno en tiempo de build.
 * - Las respuestas usan `next: { revalidate: 3600, tags: ['posts'] }`
 *   para habilitar ISR con revalidación por tag y tiempo.
 *
 * Resiliencia en build: la API en Render Free se duerme a los 15 min sin
 * tráfico y tarda 30-60 s en arrancar (cold start). Por eso cada request
 * usa un timeout amplio (`REQUEST_TIMEOUT_MS`) y, si falla por el arranque
 * (sin conexión, timeout o error 5xx), se reintenta un par de veces con un
 * backoff corto. Con la API caliente solo hay un fetch por URL.
 */

export const DEFAULT_API_BASE_URL = "http://127.0.0.1:8000/api/v1";

/** Timeout por intento: cubre el cold start de Render Free (~30-60 s). */
const REQUEST_TIMEOUT_MS = 90_000;

/** Intentos totales por petición (1 inicial + 2 reintentos). */
const MAX_REQUEST_ATTEMPTS = 3;

/** Espera entre reintentos, suficiente para que Render termine de arrancar. */
const RETRY_DELAY_MS = 5_000;

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

async function fetchOnce<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${getApiBaseUrl()}${path}`, {
      headers: { Accept: "application/json" },
      next: { revalidate: 3600, tags: ["posts"] },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
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

async function request<T>(path: string): Promise<T> {
  let lastError: ApiError | null = null;

  for (let attempt = 1; attempt <= MAX_REQUEST_ATTEMPTS; attempt += 1) {
    try {
      return await fetchOnce<T>(path);
    } catch (error) {
      // Los errores no reintentables (p. ej. 404) se lanzan de inmediato.
      if (!(error instanceof ApiError) || !isRetryable(error)) {
        throw error;
      }
      lastError = error;
      if (attempt < MAX_REQUEST_ATTEMPTS) {
        await delay(RETRY_DELAY_MS);
      }
    }
  }

  throw lastError;
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
