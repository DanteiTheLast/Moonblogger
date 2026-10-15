import { createHash } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";

// This module is imported only by server route handlers. Tokens never cross into
// client JSON, props, browser storage, URLs, or logging.
const ACCESS_COOKIE = "moon_editor_access";
const REFRESH_COOKIE = "moon_editor_refresh";
const COOKIE_PATH = "/api/editor";
const MAX_BODY_BYTES = 256 * 1024;
const TIMEOUT_MS = 65_000; // Render can need a cold start; writes are never retried.
const PRIVATE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "CDN-Cache-Control": "no-store",
  "Vercel-CDN-Cache-Control": "no-store",
  "Cross-Origin-Resource-Policy": "same-origin",
  "X-Content-Type-Options": "nosniff",
  Vary: "Cookie",
};
type Pair = { access: string; refresh: string };
class EditorError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}
function apiBase(): string {
  const base = process.env.API_BASE_URL ?? "http://127.0.0.1:8000/api/v1";
  const url = new URL(base);
  if (url.username || url.password || url.search || url.hash || !["https:", "http:"].includes(url.protocol)) {
    throw new EditorError(503, "La configuración del editor no está disponible.");
  }
  if (process.env.NODE_ENV === "production" && url.protocol !== "https:") {
    throw new EditorError(503, "El editor necesita una conexión segura con el servidor.");
  }
  return base.replace(/\/+$/, "");
}
export function editorJson(value: unknown, status = 200): NextResponse {
  return NextResponse.json(value, { status, headers: PRIVATE_HEADERS });
}
function failure(error: unknown): NextResponse {
  return editorJson({ detail: error instanceof EditorError ? error.message : "No se pudo conectar con MoonBlogger. Conserva tu texto e inténtalo de nuevo." }, error instanceof EditorError ? error.status : 503);
}
function mutationGuard(request: NextRequest): void {
  if (request.headers.get("origin") !== request.nextUrl.origin ||
      request.headers.get("x-moonblogger-editor") !== "1" ||
      request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json") {
    throw new EditorError(403, "La solicitud debe venir del editor de MoonBlogger.");
  }
  const site = request.headers.get("sec-fetch-site");
  if (site && site !== "same-origin" && site !== "none") throw new EditorError(403, "Origen no permitido.");
}
async function readJson(request: NextRequest, allowEmpty = false): Promise<unknown> {
  const size = request.headers.get("content-length");
  if (size && (!/^\d+$/.test(size) || Number(size) > MAX_BODY_BYTES)) throw new EditorError(413, "El texto es demasiado grande.");
  if (!request.body) {
    if (allowEmpty) return undefined;
    throw new EditorError(400, "Falta el contenido de la solicitud.");
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      total += part.value.byteLength;
      if (total > MAX_BODY_BYTES) { await reader.cancel(); throw new EditorError(413, "El texto es demasiado grande."); }
      chunks.push(part.value);
    }
    if (!total && allowEmpty) return undefined;
    const data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (typeof data !== "object" || data === null || Array.isArray(data)) throw new Error("Invalid JSON shape");
    return data;
  } catch (error) {
    if (error instanceof EditorError) throw error;
    throw new EditorError(400, "La solicitud no contiene JSON válido.");
  } finally { reader.releaseLock(); }
}
function claims(token: string): { exp: number; user_id: string | number } | null {
  try {
    if (token.length > 8192 || token.split(".").length !== 3) return null;
    const value = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
    return Number.isSafeInteger(value.exp) && (typeof value.user_id === "string" || typeof value.user_id === "number") ? value : null;
  } catch { return null; }
}
function validPair(value: unknown): value is Pair {
  const pair = value as Pair;
  return !!pair && typeof pair.access === "string" && typeof pair.refresh === "string" && !!claims(pair.access) && !!claims(pair.refresh);
}
function setPair(response: NextResponse, pair: Pair): NextResponse {
  for (const [name, token] of [[ACCESS_COOKIE, pair.access], [REFRESH_COOKIE, pair.refresh]]) {
    const exp = claims(token)!.exp;
    response.cookies.set(name, token, { httpOnly: true, secure: process.env.NODE_ENV === "production", sameSite: "strict", path: COOKIE_PATH, maxAge: Math.max(0, exp - Math.floor(Date.now() / 1000)) });
  }
  return response;
}
function clearPair(response: NextResponse): NextResponse {
  for (const name of [ACCESS_COOKIE, REFRESH_COOKIE]) response.cookies.set(name, "", { httpOnly: true, secure: process.env.NODE_ENV === "production", sameSite: "strict", path: COOKIE_PATH, maxAge: 0 });
  return response;
}
async function backend(path: string, method: string, body?: unknown, access?: string): Promise<Response> {
  return fetch(`${apiBase()}${path}`, {
    method, headers: { Accept: "application/json", ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...(access ? { Authorization: `Bearer ${access}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    cache: "no-store", redirect: "error", signal: AbortSignal.timeout(TIMEOUT_MS),
  });
}
async function responseJson(response: Response): Promise<unknown> {
  try { return await response.json(); } catch { throw new EditorError(502, "El servidor devolvió una respuesta inesperada."); }
}
// Rotation is blacklisted by Django. Coalesce concurrent requests in this worker
// and briefly reuse their outcome. The UI also serializes requests. Across workers
// a losing 401 NEVER deletes cookies: a successful parallel rotation must survive.
const rotations = new Map<string, { expires: number; promise: Promise<Pair> }>();
async function rotate(refresh: string): Promise<Pair> {
  const key = createHash("sha256").update(`${apiBase()}\n${refresh}`).digest("hex");
  const now = Date.now();
  for (const [entryKey, entry] of rotations) if (entry.expires < now) rotations.delete(entryKey);
  const pending = rotations.get(key);
  if (pending) return pending.promise;
  const promise = (async () => {
    const response = await backend("/auth/refresh/", "POST", { refresh });
    if (response.status === 401 || response.status === 403) throw new EditorError(401, "Tu sesión terminó. Inicia sesión de nuevo; tu texto se conserva en este dispositivo.");
    if (!response.ok) throw new EditorError(response.status === 429 ? 429 : 503, "No se pudo renovar la sesión. Espera un momento e inténtalo de nuevo.");
    const value = await responseJson(response);
    if (!validPair(value)) throw new EditorError(502, "No se pudo renovar la sesión de forma segura.");
    return value;
  })();
  if (rotations.size >= 256) rotations.delete(rotations.keys().next().value!);
  rotations.set(key, { expires: now + TIMEOUT_MS + 15_000, promise });
  try {
    const pair = await promise;
    rotations.set(key, { expires: Date.now() + 15_000, promise });
    return pair;
  } catch (error) { rotations.delete(key); throw error; }
}
function requireOwner(request: NextRequest, access: string): void {
  const expected = request.headers.get("x-moonblogger-editor-owner");
  let actual: string | null = null;
  try { actual = ownerKey(access); } catch { /* A malformed or mismatched token must never authorize a different owner. */ }
  if (!expected || !/^[a-f0-9]{32}$/.test(expected) || expected !== actual) {
    throw new EditorError(401, "La cuenta de esta pestaña cambió. Vuelve a iniciar sesión para continuar con tu escrito.");
  }
}
async function authenticated(request: NextRequest, path: string, method: string, body?: unknown, checkOwner = false): Promise<{ response: Response; pair?: Pair; access: string }> {
  let access = request.cookies.get(ACCESS_COOKIE)?.value;
  const refresh = request.cookies.get(REFRESH_COOKIE)?.value;
  let pair: Pair | undefined;
  if (!access) {
    if (!refresh) throw new EditorError(401, "Inicia sesión para abrir el editor.");
    if (checkOwner) requireOwner(request, refresh);
    pair = await rotate(refresh); access = pair.access;
  }
  if (checkOwner) requireOwner(request, access);
  let response = await backend(path, method, body, access);
  if (response.status === 401 && !pair && refresh) {
    if (checkOwner) requireOwner(request, refresh);
    pair = await rotate(refresh); access = pair.access;
    if (checkOwner) requireOwner(request, access);
    response = await backend(path, method, body, access);
  }
  return { response, pair, access };
}
function ownerKey(access: string): string {
  // Only called AFTER Django has accepted this token or issued it at login.
  const subject = claims(access);
  if (!subject) throw new EditorError(502, "La sesión no tiene un identificador válido.");
  return createHash("sha256").update(`${apiBase()}\n${subject.user_id}`).digest("hex").slice(0, 32);
}
export async function editorSession(request: NextRequest): Promise<NextResponse> {
  try {
    if (request.nextUrl.search) throw new EditorError(400, "Parámetros no permitidos.");
    if (request.method !== "GET") mutationGuard(request);
    if (request.method === "DELETE") return clearPair(editorJson({ authenticated: false, ownerKey: null }));
    if (request.method === "POST") {
      const input = await readJson(request) as Record<string, unknown>;
      if (Object.keys(input).some((key) => !["username", "password"].includes(key)) || typeof input.username !== "string" || !input.username.trim() || input.username.length > 150 || typeof input.password !== "string" || !input.password || input.password.length > 4096) throw new EditorError(400, "Escribe tu usuario y contraseña.");
      const response = await backend("/auth/login/", "POST", { username: input.username.trim(), password: input.password });
      if (response.status === 401 || response.status === 400) throw new EditorError(401, "Usuario o contraseña incorrectos.");
      if (!response.ok) throw new EditorError(response.status === 429 ? 429 : 503, response.status === 429 ? "Demasiados intentos. Espera un momento antes de volver a entrar." : "El servidor no está disponible. Inténtalo de nuevo.");
      const pair = await responseJson(response);
      if (!validPair(pair)) throw new EditorError(502, "No se pudo iniciar una sesión segura.");
      return setPair(editorJson({ authenticated: true, ownerKey: ownerKey(pair.access) }), pair);
    }
    if (request.method !== "GET") throw new EditorError(405, "Método no permitido.");
    if (!request.cookies.has(ACCESS_COOKIE) && !request.cookies.has(REFRESH_COOKIE)) return editorJson({ authenticated: false, ownerKey: null });
    const result = await authenticated(request, "/tags/", "GET");
    if (result.response.status === 401) return editorJson({ authenticated: false, ownerKey: null });
    if (!result.response.ok) throw new EditorError(503, "No se pudo comprobar la sesión. Inténtalo de nuevo.");
    const response = editorJson({ authenticated: true, ownerKey: ownerKey(result.access) });
    return result.pair ? setPair(response, result.pair) : response;
  } catch (error) {
    if (request.method === "GET" && error instanceof EditorError && error.status === 401) return editorJson({ authenticated: false, ownerKey: null });
    return failure(error);
  }
}
function allowedPath(request: NextRequest, segments: string[]): string {
  const path = segments.join("/");
  const id = "[1-9][0-9]{0,14}";
  const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  const rules: [RegExp, string[]][] = [
    [/^tags$/, ["GET"]], [/^posts$/, ["GET", "POST"]],
    [new RegExp(`^posts/${id}$`), ["GET", "PATCH"]],
    [new RegExp(`^posts/${id}/media/(upload-intents|complete)$`), ["POST"]],
    [new RegExp(`^posts/${id}/media/read-urls$`), ["GET"]],
    [new RegExp(`^posts/${id}/media/layout$`), ["PUT"]],
    [new RegExp(`^posts/${id}/media/${uuid}$`), ["PATCH", "DELETE"]],
  ];
  if (!rules.some(([pattern, methods]) => pattern.test(path) && methods.includes(request.method))) throw new EditorError(404, "Ruta del editor no disponible.");
  const query = request.nextUrl.searchParams;
  if (query.size) {
    if (path !== "posts" || request.method !== "GET" || [...query.keys()].some((key) => !["page", "status"].includes(key)) || [...query.keys()].some((key) => query.getAll(key).length !== 1)) throw new EditorError(400, "Filtros del editor no válidos.");
    if (query.has("page") && !/^[1-9][0-9]{0,5}$/.test(query.get("page")!)) throw new EditorError(400, "Página no válida.");
    if (query.has("status") && !["draft", "published"].includes(query.get("status")!)) throw new EditorError(400, "Estado no válido.");
  }
  return `/${path}/${query.size ? `?${query}` : ""}`;
}
function safeValidation(value: unknown, depth = 0): unknown {
  if (depth > 3) return "Revisa este campo.";
  if (typeof value === "string") return value.slice(0, 500);
  if (Array.isArray(value)) return value.slice(0, 20).map((item) => safeValidation(item, depth + 1));
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).slice(0, 30).map(([key, val]) => [key.slice(0, 80), safeValidation(val, depth + 1)]));
  return "Revisa este campo.";
}
export async function editorProxy(request: NextRequest, segments: string[]): Promise<NextResponse> {
  try {
    const path = allowedPath(request, segments);
    const mutation = request.method !== "GET";
    if (mutation) mutationGuard(request);
    const body = mutation ? await readJson(request, request.method === "DELETE") : undefined;
    const result = await authenticated(request, path, request.method, body, true);
    let response: NextResponse;
    if (result.response.status === 204) response = new NextResponse(null, { status: 204, headers: PRIVATE_HEADERS });
    else if (result.response.ok) response = editorJson(await responseJson(result.response), result.response.status);
    else if (result.response.status === 400) response = editorJson(safeValidation(await responseJson(result.response)), 400);
    else {
      const status = result.response.status;
      const message = status === 401 ? "Tu sesión terminó. Inicia sesión de nuevo." : status === 403 ? "No tienes permiso para realizar esta acción." : status === 404 ? "No se encontró la publicación o el archivo." : status === 429 ? "Demasiadas solicitudes. Espera un momento e inténtalo de nuevo." : "No se pudo completar la operación. Conserva tu texto e inténtalo de nuevo.";
      response = editorJson({ detail: message }, status >= 500 ? 503 : [401, 403, 404, 409, 429].includes(status) ? status : 502);
    }
    return result.pair ? setPair(response, result.pair) : response;
  } catch (error) { return failure(error); }
}
