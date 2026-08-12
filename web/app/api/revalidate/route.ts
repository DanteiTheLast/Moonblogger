import { revalidateTag } from "next/cache";
import { NextRequest, NextResponse } from "next/server";

/**
 * Webhook para revalidación bajo demanda (ISR tag-based).
 *
 * POST /api/revalidate
 *
 * Headers requeridos:
 *   X-Revalidate-Secret: <sha256(REVALIDATE_SECRET)>
 *
 * Body (opcional):
 *   { "tag": "posts" }  // por defecto "posts"
 *
 * Flujo:
 * 1. Verifica el header `X-Revalidate-Secret` contra el hash SHA-256
 *    de la variable de entorno `REVALIDATE_SECRET`.
 * 2. Si es válido: ejecuta `revalidateTag('posts', { expire: 0 })` para
 *    invalidar inmediatamente todas las páginas con ese tag.
 * 3. Devuelve 200 con { revalidated: true, now: <timestamp> }.
 * 4. Si el secret es inválido o falta: devuelve 401.
 *
 * Configuración en Vercel:
 *   - REVALIDATE_SECRET: string aleatoria (mismo que WEB_REVALIDATE_SECRET en Render)
 *
 * Configuración en Render (Django):
 *   - WEB_REVALIDATE_SECRET: mismo valor que REVALIDATE_SECRET
 *   - Webhook POST a https://<dominio-vercel>/api/revalidate
 *     con header X-Revalidate-Secret: <sha256(WEB_REVALIDATE_SECRET)>
 */

export const runtime = "nodejs";

async function sha256Hex(input: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(input);
  const buffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(buffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function verifySecret(request: NextRequest): Promise<boolean> {
  const provided = request.headers.get("X-Revalidate-Secret");
  if (!provided) {
    return false;
  }

  const secret = process.env.REVALIDATE_SECRET;
  if (!secret) {
    // Sin secret configurado, no se permite revalidación.
    return false;
  }

  const expected = await sha256Hex(secret);
  // Comparación en tiempo constante para evitar timing attacks.
  if (provided.length !== expected.length) {
    return false;
  }
  let result = 0;
  for (let i = 0; i < provided.length; i += 1) {
    result |= provided.charCodeAt(i) ^ expected.charCodeAt(i);
  }
  return result === 0;
}

export async function POST(request: NextRequest) {
  const isValid = await verifySecret(request);
  if (!isValid) {
    return NextResponse.json(
      { error: "Invalid or missing X-Revalidate-Secret" },
      { status: 401 },
    );
  }

  // Body opcional para especificar tag; por defecto "posts".
  let tag = "posts";
  try {
    const body = await request.json();
    if (body && typeof body.tag === "string" && body.tag.trim()) {
      tag = body.tag.trim();
    }
  } catch {
    // Body no es JSON válido o no existe; usar default.
  }

  // `expire: 0` invalida inmediatamente (sin stale-while-revalidate).
  revalidateTag(tag, { expire: 0 });

  return NextResponse.json({
    revalidated: true,
    tag,
    now: new Date().toISOString(),
  });
}

// Otros métodos no permitidos.
export async function GET() {
  return NextResponse.json(
    { error: "Method not allowed. Use POST." },
    { status: 405 },
  );
}