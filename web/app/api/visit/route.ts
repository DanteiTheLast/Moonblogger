import { createHmac } from "node:crypto";
import { isIP } from "node:net";
import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

const validPath = (path: unknown): path is string =>
  path === "/" || (typeof path === "string" && /^\/posts\/[^/]+$/.test(path));

function isPublicIp(value: string) {
  const ip = value.trim();
  if (!isIP(ip)) return false;
  if (ip.includes(":")) {
    const lower = ip.toLowerCase();
    return lower !== "::1" && !lower.startsWith("fc") && !lower.startsWith("fd") && !lower.startsWith("fe8") && !lower.startsWith("fe9") && !lower.startsWith("fea") && !lower.startsWith("feb");
  }
  const octets = ip.split(".").map(Number);
  const [a, b] = octets;
  return a !== 10 && a !== 127 && !(a === 169 && b === 254) && !(a === 172 && b >= 16 && b <= 31) && !(a === 192 && b === 168) && !(a === 100 && b >= 64 && b <= 127) && !(a >= 224);
}

function clientIp(request: NextRequest) {
  // Vercel's forwarded chain is trusted as supplied by the platform; arbitrary
  // client IP spoofing cannot be ruled out when this handler runs elsewhere.
  const forwarded = request.headers.get("x-forwarded-for")?.split(",") ?? [];
  return [...forwarded, request.headers.get("x-real-ip") ?? ""].find(isPublicIp) ?? "unknown";
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    if (!validPath(body?.path)) return new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } });
    const secret = process.env.VISIT_FORWARDING_SECRET;
    const apiBase = process.env.API_BASE_URL;
    if (!secret || !apiBase) return new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } });
    const ip = clientIp(request);
    const ua = request.headers.get("user-agent") ?? "";
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const signature = createHmac("sha256", secret).update(`${timestamp}\n${ip}\n${body.path}\n${ua}`).digest("hex");
    await fetch(`${apiBase.replace(/\/$/, "")}/internal/public-visits/`, {
      method: "POST", headers: { "Content-Type": "application/json", "X-Visitor-IP": ip, "X-Visitor-User-Agent": ua, "X-Visit-Timestamp": timestamp, "X-Visit-Signature": signature },
      body: JSON.stringify({ path: body.path }), cache: "no-store",
    });
  } catch { /* Tracking is deliberately non-blocking. */ }
  return new NextResponse(null, { status: 202, headers: { "Cache-Control": "no-store" } });
}
