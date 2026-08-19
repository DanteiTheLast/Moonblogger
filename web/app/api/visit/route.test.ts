import { createHmac } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { POST, canonicalIp } from "./route";

const SECRET = "visit-test-secret";

function request(body: unknown, headers: Record<string, string> = {}) {
  return new NextRequest("https://web.test/api/visit", {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

function expectNoStore(response: Response) {
  expect(response.headers.get("cache-control")).toBe("no-store");
}

afterEach(() => {
  delete process.env.VISIT_FORWARDING_SECRET;
  delete process.env.API_BASE_URL;
  vi.restoreAllMocks();
});

describe("POST /api/visit", () => {
  it("ignora paths invalidos y configuracion ausente", async () => {
    process.env.VISIT_FORWARDING_SECRET = SECRET;
    process.env.API_BASE_URL = "https://api.test/api/v1";
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));

    const invalid = await POST(request({ path: "/admin" }, { "x-real-ip": "192.0.2.1" }));
    expect(invalid.status).toBe(204);
    expect(fetchMock).not.toHaveBeenCalled();
    expectNoStore(invalid);

    delete process.env.VISIT_FORWARDING_SECRET;
    const missingSecret = await POST(request({ path: "/" }, { "x-real-ip": "192.0.2.1" }));
    expect(missingSecret.status).toBe(204);
    expectNoStore(missingSecret);
  });

  it("envia body, headers y HMAC exactos, usando XFF y UA canonicalizados", async () => {
    process.env.VISIT_FORWARDING_SECRET = SECRET;
    process.env.API_BASE_URL = "https://api.test/api/v1/";
    vi.spyOn(Date, "now").mockReturnValue(1_700_000_000_123);
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));
    const ua = "  Moon\tBlogger  " + "x".repeat(600);
    const response = await POST(request({ path: "/posts/hello-world", event_id: "client-id" }, {
      "x-forwarded-for": " 2001:0DB8:0:0:0:0:0:1, 198.51.100.2",
      "x-real-ip": "198.51.100.3",
      "user-agent": ua,
    }));

    expect(response.status).toBe(202);
    expectNoStore(response);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    const headers = new Headers(init?.headers);
    const ip = "2001:db8::1";
    const cleanUa = ("Moon Blogger  " + "x".repeat(600)).replace(/\s+/g, " ").trim().slice(0, 512);
    const eventId = JSON.parse(String(init?.body)).event_id;
    expect(url).toBe("https://api.test/api/v1/internal/public-visits/");
    expect(init?.method).toBe("POST");
    expect(init?.cache).toBe("no-store");
    expect(JSON.parse(String(init?.body))).toEqual({ path: "/posts/hello-world", event_id: eventId });
    expect(eventId).toMatch(/^[0-9a-f-]{36}$/);
    expect(headers.get("content-type")).toBe("application/json");
    expect(headers.get("x-visitor-ip")).toBe(ip);
    expect(headers.get("x-visitor-user-agent")).toBe(cleanUa);
    expect(headers.get("x-visitor-timestamp")).toBe("1700000000");
    expect(headers.get("x-visitor-signature")).toBe(createHmac("sha256", SECRET)
      .update(`1700000000\n${eventId}\n${ip}\n/posts/hello-world\n${cleanUa}`).digest("hex"));
  });

  it("acepta X-Real-IP, ::, y no bloquea con errores del backend", async () => {
    process.env.VISIT_FORWARDING_SECRET = SECRET;
    process.env.API_BASE_URL = "https://api.test";
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 500 }));
    const response = await POST(request({ path: "/" }, { "x-real-ip": "::" }));
    expect(response.status).toBe(202);
    expectNoStore(response);

    vi.spyOn(globalThis, "fetch").mockRejectedValueOnce(new Error("offline"));
    const rejected = await POST(request({ path: "/" }, { "x-real-ip": "127.0.0.1" }));
    expect(rejected.status).toBe(202);
    expectNoStore(rejected);
  });
});

describe("canonicalIp", () => {
  it("canonicaliza IPv6, incluido ::, y rechaza valores invalidos", () => {
    expect(canonicalIp("2001:0DB8:0:0:0:0:0:1")).toBe("2001:db8::1");
    expect(canonicalIp("::")).toBe("::");
    expect(canonicalIp("not-an-ip")).toBeNull();
  });
});
