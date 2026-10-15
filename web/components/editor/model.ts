import type { CarouselTransition, Tag } from "@/lib/types";

export type EditorStatus = "draft" | "published";
export interface PrivateMedia {
  id: string; kind: "image" | "video"; state: string; position: number | null;
  is_cover: boolean; mime_type: string; alt_text: string; caption: string;
}
export interface PrivatePost {
  id: number; title: string; content: string; slug: string; status: EditorStatus;
  updated_at: string; tags: Tag[]; carousel_transition: CarouselTransition; media: PrivateMedia[];
}
export interface EditorMedia {
  key: string; id?: string; kind: "image" | "video"; mime_type: string;
  alt_text: string; caption: string; is_cover: boolean; ready: boolean;
  uploadUrl?: string; uploaded?: boolean; uploadAttempted?: boolean; preview?: string; photo?: import("@/lib/editorMedia").PreparedEditorPhoto;
}
export interface EditorValues {
  title: string; content: string; status: EditorStatus; transition: CarouselTransition;
  tags: Tag[]; media: EditorMedia[]; deleted: string[]; workingId: number | null;
  originalStatus: EditorStatus; tagsDirty: boolean; mediaDirty: boolean; uncertainCreation?: boolean;
}
export const blankValues = (): EditorValues => ({ title: "", content: "", status: "draft", transition: "slide", tags: [], media: [], deleted: [], workingId: null, originalStatus: "draft", tagsDirty: false, mediaDirty: false });
export function valuesFromPost(post: PrivatePost): EditorValues {
  return { title: post.title, content: post.content, status: post.status, transition: post.carousel_transition,
    tags: post.tags || [], workingId: post.id, originalStatus: post.status, deleted: [], tagsDirty: false, mediaDirty: false,
    media: post.media.filter(item => item.state === "ready" && item.position !== null).sort((a,b) => a.position! - b.position!).map(item => ({key: item.id, id: item.id, kind: item.kind, mime_type: item.mime_type, alt_text: item.alt_text, caption: item.caption, is_cover: item.is_cover, ready: true})) };
}
export function ensureCover(media: EditorMedia[]): EditorMedia[] {
  const cover = media.find(item => item.is_cover)?.key ?? media[0]?.key;
  return media.map(item => ({ ...item, is_cover: item.key === cover }));
}
export function snapshot(values: EditorValues) {
  return { version: 1 as const, title: values.title, content: values.content, status: values.status,
    transition: values.transition, tags: values.tags.map(tag => ({slug:tag.slug,name:tag.name})), workingId: values.workingId, originalStatus: values.originalStatus,
    tagsDirty: values.tagsDirty, mediaDirty: values.mediaDirty, deleted: values.deleted,
    missingPhotos: values.media.some(item => !item.id || (!item.ready && !item.uploaded)), uncertainCreation: Boolean(values.uncertainCreation),
    media: values.media.filter(item => item.id).map(item => ({key: item.key, id: item.id!, kind: item.kind, mime_type: item.mime_type, alt_text: item.alt_text, caption: item.caption, is_cover: item.is_cover, ready: item.ready, uploaded: Boolean(item.uploaded), uploadAttempted: Boolean(item.uploadAttempted)})) };
}
export type Draft = ReturnType<typeof snapshot>;
const transitions = ["slide", "fade", "bubble", "none"];
export function parseDraft(value: string, expectedId: number | null): Draft | null {
  try {
    const item = JSON.parse(value) as Draft;
    if (!item || item.version !== 1 || typeof item.title !== "string" || item.title.length > 200 || typeof item.content !== "string" || item.content.length > 1000000 || !["draft","published"].includes(item.status) || !["draft","published"].includes(item.originalStatus) || !transitions.includes(item.transition)) return null;
    if (item.workingId !== null && (!Number.isSafeInteger(item.workingId) || item.workingId < 1)) return null;
    if (expectedId !== null && item.workingId !== expectedId) return null;
    if (!Array.isArray(item.tags) || item.tags.length > 5 || item.tags.some(tag => typeof tag.slug !== "string" || !/^[a-z0-9-]{1,50}$/.test(tag.slug) || typeof tag.name !== "string")) return null;
    if (!Array.isArray(item.media) || item.media.length > 10 || item.media.some(media => !/^[0-9a-f-]{36}$/.test(media.id) || !["image","video"].includes(media.kind) || typeof media.alt_text !== "string" || typeof media.caption !== "string" || typeof media.ready !== "boolean" || typeof media.is_cover !== "boolean")) return null;
    if (!Array.isArray(item.deleted) || item.deleted.some(id => !/^[0-9a-f-]{36}$/.test(id))) return null;
    // Rebuild the allowlisted shape: ignore injected URLs, tokens, files and unknown fields.
    const clean = snapshot({ ...item, media: item.media.map(media => ({ key: media.id, id: media.id, kind: media.kind, mime_type: String(media.mime_type), alt_text: media.alt_text, caption: media.caption, is_cover: media.is_cover, ready: media.ready, uploaded: Boolean(media.uploaded), uploadAttempted: Boolean(media.uploadAttempted) })) });
    return { ...clean, missingPhotos: Boolean(item.missingPhotos), tagsDirty: Boolean(item.tagsDirty), mediaDirty: Boolean(item.mediaDirty) };
  } catch { return null; }
}
export const draftKey = (owner: string, id: number | null) => `moonblogger-editor:v1:${encodeURIComponent(owner)}:${id ?? "new"}`;

export class EditorRequestError extends Error {
  constructor(message: string, public status: number = 0) { super(message); }
}
export async function editorRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response;
  try { response = await fetch(`/api/editor/${path}`, { ...options, credentials: "same-origin", cache: "no-store", headers: { ...(!["GET", "HEAD"].includes(options.method ?? "GET") ? { "Content-Type": "application/json" } : {}), "X-MoonBlogger-Editor": "1", ...options.headers } }); }
  catch { throw new EditorRequestError("No se pudo conectar. Tu escrito sigue aquí; revisa la conexión antes de reintentar."); }
  if (!response.ok) {
    let message = response.status === 401 ? "Tu sesión terminó. Vuelve a entrar para continuar con este escrito." : response.status === 429 ? "Hay demasiados intentos. Espera un momento antes de continuar." : "No se pudo completar la operación. Tu escrito sigue aquí.";
    if (response.status === 400) {
      try { const errors = await response.json(); const details = Object.values(errors).flat().filter(value => typeof value === "string"); if (details.length) message = details.slice(0,3).join(" "); } catch { /* keep safe message */ }
    }
    throw new EditorRequestError(message, response.status);
  }
  return response.status === 204 ? undefined as T : await response.json() as T;
}
