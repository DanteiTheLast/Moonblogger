import { createHmac, randomUUID } from "node:crypto";
import { canonicalIp } from "@/lib/canonicalIp";
import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

const validPath = (path: unknown): path is string =>
  path === "/" || (typeof path === "string" && /^\/posts\/[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(path));


function clientIp(request: NextRequest) {
  // Vercel's forwarded chain is trusted as supplied by the platform; arbitrary
  // client IP spoofing cannot be ruled out when this handler runs elsewhere.
  const forwarded = request.headers.get("x-forwarded-for")?.split(",") ?? [];
  for (const candidate of [...forwarded, request.headers.get("x-real-ip") ?? ""]) {
    const ip = canonicalIp(candidate);
    if (ip) return ip;
  }
  return null;
}

function noStore(status: number) {
  return new NextResponse(null, { status, headers: { "Cache-Control": "no-store" } });
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    if (!validPath(body?.path)) return noStore(204);
    const secret = process.env.VISIT_FORWARDING_SECRET;
    const apiBase = process.env.API_BASE_URL;
    if (!secret || !apiBase) return noStore(204);
    const ip = clientIp(request);
    if (!ip) return noStore(204);
    const ua = (request.headers.get("user-agent") ?? "").replace(/\s+/g, " ").trim().slice(0, 512);
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const eventId = randomUUID();
    const signature = createHmac("sha256", secret).update(`${timestamp}\n${eventId}\n${ip}\n${body.path}\n${ua}`).digest("hex");
    const response = await fetch(`${apiBase.replace(/\/$/, "")}/internal/public-visits/`, {
      method: "POST", headers: { "Content-Type": "application/json", "X-Visitor-IP": ip, "X-Visitor-User-Agent": ua, "X-Visitor-Timestamp": timestamp, "X-Visitor-Signature": signature },
      body: JSON.stringify({ path: body.path, event_id: eventId }), cache: "no-store",
    });
    if (!response.ok) return noStore(202);
  } catch { /* Tracking is deliberately non-blocking. */ }
  return new NextResponse(null, { status: 202, headers: { "Cache-Control": "no-store" } });
}
