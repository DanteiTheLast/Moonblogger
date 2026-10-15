import { NextRequest } from "next/server";
import { editorProxy } from "@/lib/editorServer";
export const dynamic = "force-dynamic";
export const runtime = "nodejs";
type Context = { params: Promise<{ path: string[] }> };
async function handle(request: NextRequest, context: Context) {
  return editorProxy(request, (await context.params).path);
}
export const GET = handle;
export const POST = handle;
export const PATCH = handle;
export const PUT = handle;
export const DELETE = handle;

export const HEAD = handle;
export const OPTIONS = handle;
