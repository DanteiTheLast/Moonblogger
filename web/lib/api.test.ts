import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  DEFAULT_API_BASE_URL,
  getApiBaseUrl,
  getPublishedPostBySlug,
  getPublishedPosts,
  getPublishedPostsPage,
  getAllPublishedPosts,
} from "./api";
vi.mock("next/cache", () => ({ unstable_cache: (callback: unknown) => callback }));

import type { Post, PostListItem, PostListResponse } from "./types";

const MOCK_POST: Post = {
  id: 1,
  slug: "bienvenida",
  title: "Bienvenida",
  content: "Hola mundo",
  status: "published",
  created_at: "2026-08-12T00:00:00Z",
  updated_at: "2026-08-12T00:00:00Z",
  published_at: "2026-08-12T00:00:00Z",
  carousel_transition: "slide",
  cover: null,
  media_count: 0,
  media: [],
};

const MOCK_LIST_POST: PostListItem = {
  ...MOCK_POST,
};

function mockFetch(status: number, body: unknown): ReturnType<typeof vi.fn> {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response);
}

/**
 * Con `vi.useFakeTimers()` activo, deja correr los backoff de reintento
 * (5 s por espera) hasta que la promesa se resuelve o se rechaza.
 */
async function flushRetryTimers(promise: Promise<unknown>): Promise<void> {
  let settled = false;
  promise.then(
    () => {
      settled = true;
    },
    () => {
      settled = true;
    },
  );
  for (let i = 0; i < 10 && !settled; i += 1) {
    await vi.advanceTimersByTimeAsync(5_000);
  }
  expect(settled).toBe(true);
}

beforeEach(() => {
  process.env.API_BASE_URL = "http://api.test/api/v1";
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("getApiBaseUrl", () => {
  it("usa la variable de entorno si está definida", () => {
    process.env.API_BASE_URL = "http://api.test/api/v1";
    expect(getApiBaseUrl()).toBe("http://api.test/api/v1");
  });

  it("usa el default local cuando no hay variable de entorno", () => {
    delete process.env.API_BASE_URL;
    expect(getApiBaseUrl()).toBe(DEFAULT_API_BASE_URL);
  });
});

describe("getPublishedPosts", () => {
  it("parsea la respuesta paginada de DRF y devuelve results", async () => {
    const payload: PostListResponse = {
      count: 1,
      next: null,
      previous: null,
      results: [MOCK_LIST_POST],
    };
    const fetchMock = mockFetch(200, payload);
    vi.stubGlobal("fetch", fetchMock);

    const posts = await getPublishedPosts();

    expect(posts).toEqual([MOCK_LIST_POST]);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://api.test/api/v1/public/posts/",
      expect.objectContaining({
        headers: { Accept: "application/json" },
        cache: "no-store",
        signal: expect.any(AbortSignal),
      }),
    );
  });

  it("maneja un listado vacío (paginación con results: [])", async () => {
    const payload: PostListResponse = {
      count: 0,
      next: null,
      previous: null,
      results: [],
    };
    vi.stubGlobal("fetch", mockFetch(200, payload));

    await expect(getPublishedPosts()).resolves.toEqual([]);
  });

  it("lanza ApiError si la API responde con error 5xx (con reintentos)", async () => {
    vi.useFakeTimers();
    const fetchMock = mockFetch(500, { detail: "Error interno" });
    vi.stubGlobal("fetch", fetchMock);

    const promise = getPublishedPosts();
    await flushRetryTimers(promise);

    await expect(promise).rejects.toBeInstanceOf(ApiError);
    await expect(promise).rejects.toMatchObject({ status: 500 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("lanza ApiError (status 0) si no hay conexión (con reintentos)", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockRejectedValue(new TypeError("fetch failed"));
    vi.stubGlobal("fetch", fetchMock);

    const promise = getPublishedPosts();
    await flushRetryTimers(promise);

    await expect(promise).rejects.toMatchObject({ status: 0 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("reintenta tras un fallo de conexión transitorio y tiene éxito", async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("fetch failed"))
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => MOCK_POST,
      } as Response);
    vi.stubGlobal("fetch", fetchMock);

    const promise = getPublishedPostBySlug("bienvenida");
    await flushRetryTimers(promise);

    await expect(promise).resolves.toEqual(MOCK_POST);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("no reintenta los errores 4xx", async () => {
    const fetchMock = mockFetch(404, { detail: "No encontrado." });
    vi.stubGlobal("fetch", fetchMock);

    await expect(getPublishedPostBySlug("borrador")).rejects.toMatchObject({
      status: 404,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("getPublishedPostBySlug", () => {
  it("devuelve el post para un slug existente", async () => {
    vi.stubGlobal("fetch", mockFetch(200, MOCK_POST));

    const post = await getPublishedPostBySlug("bienvenida");

    expect(post).toEqual(MOCK_POST);
    // Verifica que se usa la opción next con revalidate y tags (ISR)
    expect(fetch).toHaveBeenCalledWith(
      "http://api.test/api/v1/public/posts/bienvenida/",
      expect.objectContaining({
        headers: { Accept: "application/json" },
        cache: "no-store",
        signal: expect.any(AbortSignal),
      }),
    );
  });

  it("lanza ApiError con status 404 si el post no existe o es borrador", async () => {
    vi.stubGlobal(
      "fetch",
      mockFetch(404, { detail: "No encontrado." }),
    );

    const error = await getPublishedPostBySlug("borrador").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    if (error instanceof ApiError) {
      expect(error.status).toBe(404);
    }
  });
});

describe("archivo paginado", () => {
  it("mantiene envelope para navegar sin seguir URLs arbitrarias de la API", async () => {
    const payload = { count: 21, next: null, previous: "https://other.invalid", results: [MOCK_LIST_POST] };
    const fetchMock = mockFetch(200, payload);
    vi.stubGlobal("fetch", fetchMock);
    await expect(getPublishedPostsPage(2)).resolves.toEqual(payload);
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/v1/public/posts/?page=2");
  });
  it("recorre todas las páginas del archivo para el sitemap", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ next: "https://untrusted.invalid", results: [MOCK_LIST_POST] }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ next: null, results: [{ ...MOCK_LIST_POST, id: 2 }] }) });
    vi.stubGlobal("fetch", fetchMock);
    const posts = await getAllPublishedPosts(getApiBaseUrl());
    expect(posts.map(post => post.id)).toEqual([1, 2]);
    expect(fetchMock.mock.calls[1][0]).toBe("http://api.test/api/v1/public/posts/?page=2");
  });
  it("no devuelve un archivo parcial si falla una página posterior", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ next: "next", results: [MOCK_LIST_POST] }) })
      .mockResolvedValue({ ok: false, status: 404 });
    vi.stubGlobal("fetch", fetchMock);
    await expect(getAllPublishedPosts(getApiBaseUrl())).rejects.toMatchObject({ status: 404 });
  });
});

describe("presupuesto de arranque en frío", () => {
  it("comparte 75 s entre intentos en vez de repetir timeouts completos", async () => {
    vi.useFakeTimers();
    const timeout = vi.spyOn(AbortSignal, "timeout");
    vi.stubGlobal("fetch", vi.fn()
      .mockImplementationOnce(() => new Promise((_, reject) => setTimeout(() => reject(new TypeError("timeout")), 65_000)))
      .mockRejectedValue(new TypeError("offline")));
    const result = getPublishedPosts().catch(error => error);
    await vi.advanceTimersByTimeAsync(75_000);
    expect(await result).toMatchObject({ status: 0 });
    expect(timeout.mock.calls.map(call => call[0])).toEqual([65_000, 9_000]);
  });
});

describe("tag-filtered public API", () => {
  it("sends repeated OR parameters before pagination without duplicate slugs", async () => {
    const fetchMock = mockFetch(200, { count: 0, next: null, previous: null, results: [] });
    vi.stubGlobal("fetch", fetchMock);
    await getPublishedPostsPage(2, ["viajes", "dan", "dan"]);
    expect(fetchMock).toHaveBeenCalledWith("http://api.test/api/v1/public/posts/?tag=dan&tag=viajes&page=2", expect.anything());
  });
});
