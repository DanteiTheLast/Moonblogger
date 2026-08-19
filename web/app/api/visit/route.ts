import { createHmac, randomUUID } from "node:crypto";
import { isIP } from "node:net";
import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

const validPath = (path: unknown): path is string =>
  path === "/" || (typeof path === "string" && /^\/posts\/[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(path));

export function canonicalIp(value: string): string | null {
  const input = value.trim();
  if (!isIP(input)) return null;
  if (!input.includes(":")) return input.split(".").map(Number).join(".");
  const parts = input.toLowerCase().split("::");
  if (parts.length > 2) return null;
  const left = parts[0] ? parts[0].split(":") : [];
  const right = parts[1] ? parts[1].split(":") : [];
  const expanded = parts.length === 2 ? [...left, ...Array(8 - left.length - right.length).fill("0"), ...right] : left;
  if (expanded.length !== 8) return null;
  const nums = expanded.map((part) => Number.parseInt(part, 16));
  if (nums.some((n) => !Number.isInteger(n) || n < 0 || n > 0xffff)) return null;
  let bestStart = -1, bestLength = 0;
  for (let i = 0; i < 8;) { if (nums[i] !== 0) { i++; continue; } let j = i; while (j < 8 && nums[j] === 0) j++; if (j - i > bestLength) { bestStart = i; bestLength = j - i; } i = j; }
  const rendered = nums.map((n) => n.toString(16));
  if (bestLength >= 2) rendered.splice(bestStart, bestLength, "");
  if (bestStart === 0 && bestLength === 8) return "::";
  return rendered.join(":").replace(/^:/, "::").replace(/:$/, "::");
}

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
