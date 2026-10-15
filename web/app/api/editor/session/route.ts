import { editorJson, editorSession } from "@/lib/editorServer";
export const dynamic = "force-dynamic";
export const runtime = "nodejs";
export const GET = editorSession;
export const POST = editorSession;
export const DELETE = editorSession;

function unsupported() { return editorJson({ detail: "Método no permitido." }, 405); }
export const PUT = unsupported;
export const PATCH = unsupported;
export const HEAD = unsupported;
export const OPTIONS = unsupported;
