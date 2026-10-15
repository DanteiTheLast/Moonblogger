import { createHash } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { editorProxy, editorSession } from "./editorServer";

const token = (id: number, expires = 900, salt = "") => `header.${Buffer.from(JSON.stringify({ user_id: id, exp: Math.floor(Date.now() / 1000) + expires, salt })).toString("base64url")}.signature`;
const pair = (id: number, salt = "") => ({ access: token(id, 900, salt), refresh: token(id, 604800, salt) });
function request(path = "session", method = "GET", body?: unknown, cookie = "", extra: Record<string, string | undefined> = {}) {
  const jwt = cookie.split(";").map(part => part.trim()).find(part => part.startsWith("moon_editor_access=") || part.startsWith("moon_editor_refresh="))?.split("=")[1];
  const identity = jwt ? JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString()).user_id : null;
  return new NextRequest(`http://localhost/api/editor/${path}`, {
    method, headers: { ...(identity !== null ? { "X-MoonBlogger-Editor-Owner": owner(identity) } : {}), ...(method === "GET" ? {} : { Origin: "http://localhost", "Content-Type": "application/json", "X-MoonBlogger-Editor": "1" }), ...(cookie ? { Cookie: cookie } : {}), ...Object.fromEntries(Object.entries(extra).filter((entry): entry is [string, string] => entry[1] !== undefined)) },
    ...(body === undefined ? {} : { body: typeof body === "string" ? body : JSON.stringify(body) }),
  });
}
const owner = (id: number) => createHash("sha256").update(`https://api.example/api/v1\n${id}`).digest("hex").slice(0, 32);
const fetched = vi.fn();
beforeEach(() => { fetched.mockReset(); vi.stubGlobal("fetch", fetched); vi.stubEnv("API_BASE_URL", "https://api.example/api/v1"); });
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("editor session cookies", () => {
  it("logs in without exposing tokens and sets scoped, secure, strict HttpOnly cookies with token lifetimes", async () => {
    vi.stubEnv("NODE_ENV", "production");
    const tokens = pair(7);
    fetched.mockResolvedValue(Response.json(tokens));
    const response = await editorSession(request("session", "POST", { username: "moon", password: "secret" }));
    expect(response.status).toBe(200);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ authenticated: true, ownerKey: expect.stringMatching(/^[a-f0-9]{32}$/) });
    expect(text).not.toContain(tokens.access);
    for (const cookie of response.cookies.getAll()) {
      expect(cookie).toMatchObject({ httpOnly: true, secure: true, sameSite: "strict", path: "/api/editor" });
      expect(cookie.maxAge).toBeGreaterThan(0);
    }
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(fetched.mock.calls[0][1]).toMatchObject({ cache: "no-store", redirect: "error" });
  });
  it("does not consider a decoded but backend-rejected token authenticated", async () => {
    fetched.mockResolvedValue(Response.json({ detail: "invalid" }, { status: 401 }));
    const response = await editorSession(request("session", "GET", undefined, `moon_editor_access=${token(1)}`));
    expect(await response.json()).toEqual({ authenticated: false, ownerKey: null });
    expect(response.cookies.getAll()).toHaveLength(0);
  });
  it("checks Django before returning a stable owner identity", async () => {
    fetched.mockResolvedValue(Response.json([]));
    const a = await editorSession(request("session", "GET", undefined, `moon_editor_access=${token(5, 700)}`));
    const b = await editorSession(request("session", "GET", undefined, `moon_editor_access=${token(5, 800)}`));
    expect(await a.json()).toEqual(await b.json());
    expect(fetched).toHaveBeenCalledTimes(2);
    expect(fetched.mock.calls[0][0]).toBe("https://api.example/api/v1/tags/");
  });
  it("logout expires both cookies", async () => {
    const response = await editorSession(request("session", "DELETE"));
    expect(response.cookies.getAll()).toHaveLength(2);
    expect(response.cookies.getAll().every((cookie) => cookie.maxAge === 0)).toBe(true);
    expect(fetched).not.toHaveBeenCalled();
  });
  it("rejects production plaintext API connections before sending a password", async () => {
    vi.stubEnv("NODE_ENV", "production"); vi.stubEnv("API_BASE_URL", "http://api.example/api/v1");
    expect((await editorSession(request("session", "POST", { username: "moon", password: "secret" }))).status).toBe(503);
    expect(fetched).not.toHaveBeenCalled();
  });
});

describe("editor request boundary", () => {
  it.each([
    { Origin: "https://evil.example" },
    { Origin: "" },
    { "X-MoonBlogger-Editor": "" },
    { "Content-Type": "text/plain" },
    { "Sec-Fetch-Site": "cross-site" },
  ])("rejects CSRF mutations before login reaches Django: %j", async (headers) => {
    const response = await editorSession(request("session", "POST", { username: "moon", password: "secret" }, "", headers));
    expect(response.status).toBe(403);
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(fetched).not.toHaveBeenCalled();
  });
  it("enforces declared and streamed 256 KiB request size without forwarding", async () => {
    const claimed = await editorSession(request("session", "POST", {}, "", { "Content-Length": "262145" }));
    const actual = await editorSession(request("session", "POST", { username: "moon", password: "a".repeat(262144) }));
    expect(claimed.status).toBe(413); expect(actual.status).toBe(413);
    expect(fetched).not.toHaveBeenCalled();
  });
  it.each([
    ["auth/login", "POST"], ["public/posts", "GET"], ["posts/1", "DELETE"], ["posts/0", "PATCH"],
    ["posts/1/media/not-a-uuid", "DELETE"], ["../auth/refresh", "POST"], ["https://evil.example", "GET"],
  ])("never proxies paths outside the explicit contract: %s %s", async (path, method) => {
    const response = await editorProxy(request("any", method, method === "GET" ? undefined : {}), path.split("/"));
    expect(response.status).toBe(404); expect(fetched).not.toHaveBeenCalled();
  });
  it.each(["posts?status=other", "posts?page=0", "posts?status=draft&status=published", "posts?url=https://evil.example", "tags?page=1"])('rejects query "%s"', async (path) => {
    const response = await editorProxy(request(path), path.split("?")[0].split("/"));
    expect(response.status).toBe(400); expect(fetched).not.toHaveBeenCalled();
  });
  it("preserves owner-scoped pagination but strips arbitrary caller authorization", async () => {
    const access = token(20);
    fetched.mockResolvedValue(Response.json({ count: 0, results: [] }));
    const response = await editorProxy(request("posts?status=draft&page=2", "GET", undefined, `moon_editor_access=${access}`, { Authorization: "Bearer attacker" }), ["posts"]);
    expect(response.status).toBe(200);
    expect(fetched.mock.calls[0][0]).toBe("https://api.example/api/v1/posts/?status=draft&page=2");
    expect(fetched.mock.calls[0][1].headers.Authorization).toBe(`Bearer ${access}`);
  });
  it("does not send an unauthenticated mutation to Django", async () => {
    expect((await editorProxy(request("posts", "POST", { title: "New" }), ["posts"])).status).toBe(401);
    expect(fetched).not.toHaveBeenCalled();
  });
  it("preserves field validation and media 204 without reflecting internal failure HTML", async () => {
    const cookie = `moon_editor_access=${token(21)}`;
    fetched.mockResolvedValueOnce(Response.json({ tag_slugs: ["Unknown tag"] }, { status: 400 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response("<html>password=sensitive</html>", { status: 500 }));
    const invalid = await editorProxy(request("posts", "POST", {}, cookie), ["posts"]);
    expect(await invalid.json()).toEqual({ tag_slugs: ["Unknown tag"] });
    const path = ["posts", "1", "media", "12345678-1234-1234-1234-123456789abc"];
    expect((await editorProxy(request(path.join("/"), "DELETE", undefined, cookie), path)).status).toBe(204);
    const failed = await editorProxy(request("posts", "GET", undefined, cookie), ["posts"]);
    expect(failed.status).toBe(503); expect(await failed.text()).not.toContain("sensitive");
  });
});

describe("refresh rotation and failure preservation", () => {
  it("refreshes once on 401 and persists the rotated pair", async () => {
    const old = pair(101, "old"), next = pair(101, "next");
    fetched.mockResolvedValueOnce(Response.json({}, { status: 401 })).mockResolvedValueOnce(Response.json(next)).mockResolvedValueOnce(Response.json({ count: 0, results: [] }));
    const response = await editorProxy(request("posts", "GET", undefined, `moon_editor_access=${old.access}; moon_editor_refresh=${old.refresh}`), ["posts"]);
    expect(response.status).toBe(200);
    expect(response.cookies.get("moon_editor_refresh")?.value).toBe(next.refresh);
    expect(fetched).toHaveBeenCalledTimes(3);
    expect(fetched.mock.calls[1][0]).toContain("auth/refresh");
    expect(fetched.mock.calls[2][1].headers.Authorization).toBe(`Bearer ${next.access}`);
  });
  it("coalesces parallel refresh calls within a worker", async () => {
    const old = pair(102, "parallel"), next = pair(102, "renewed");
    fetched.mockImplementation(async (url: string) => url.endsWith("auth/refresh/") ? Response.json(next) : Response.json([]));
    const calls = [1, 2, 3].map(() => editorProxy(request("tags", "GET", undefined, `moon_editor_refresh=${old.refresh}`), ["tags"]));
    const responses = await Promise.all(calls);
    expect(responses.every((response) => response.status === 200)).toBe(true);
    expect(fetched.mock.calls.filter(([url]) => url.endsWith("auth/refresh/"))).toHaveLength(1);
  });
  it.each([401, 429, 503])("does not erase credentials when refresh responds %s", async (status) => {
    const old = pair(103 + status, `error-${status}`);
    fetched.mockResolvedValue(Response.json({ detail: "private backend error" }, { status }));
    const response = await editorProxy(request("tags", "GET", undefined, `moon_editor_refresh=${old.refresh}`), ["tags"]);
    expect(response.status).toBe(status);
    expect(response.cookies.getAll()).toHaveLength(0);
    expect(await response.text()).not.toContain("private backend error");
  });
  it("never blindly retries a write after a network failure", async () => {
    fetched.mockRejectedValue(new Error("socket failed password=sensitive"));
    const response = await editorProxy(request("posts", "POST", { title: "May have saved" }, `moon_editor_access=${token(200)}`), ["posts"]);
    expect(response.status).toBe(503); expect(fetched).toHaveBeenCalledTimes(1);
    expect(await response.text()).not.toContain("sensitive");
  });
});


describe("cross-tab account isolation", () => {
  it("rejects every private request without a session owner", async () => {
    const response = await editorProxy(request("posts", "GET", undefined, `moon_editor_access=${token(301)}`, { "X-MoonBlogger-Editor-Owner": "" }), ["posts"]);
    expect(response.status).toBe(401); expect(fetched).not.toHaveBeenCalled();
  });
  it("does not create a post under account B from an account A tab", async () => {
    const response = await editorProxy(request("posts", "POST", { title: "Account A private draft" }, `moon_editor_access=${token(302)}`, { "X-MoonBlogger-Editor-Owner": owner(301) }), ["posts"]);
    expect(response.status).toBe(401); expect(fetched).not.toHaveBeenCalled();
    expect(response.cookies.getAll()).toHaveLength(0);
    expect(await response.text()).toContain("cuenta");
  });
  it("does not retry a write when refresh changes the account", async () => {
    const access = token(303), refresh = pair(303, "different-account").refresh;
    fetched.mockResolvedValueOnce(Response.json({}, { status: 401 })).mockResolvedValueOnce(Response.json(pair(304, "different-account-next")));
    const response = await editorProxy(request("posts", "POST", { title: "Account A" }, `moon_editor_access=${access}; moon_editor_refresh=${refresh}`, { "X-MoonBlogger-Editor-Owner": owner(303) }), ["posts"]);
    expect(response.status).toBe(401);
    expect(fetched).toHaveBeenCalledTimes(2);
    expect(fetched.mock.calls.filter(([url]) => url.endsWith("/posts/"))).toHaveLength(1);
    expect(response.cookies.getAll()).toHaveLength(0);
  });
  it("checks the owner before using refresh-only authentication for a write", async () => {
    const refresh = pair(306, "refresh-only-account").refresh;
    fetched.mockResolvedValueOnce(Response.json(pair(306, "refresh-only-next")));
    const response = await editorProxy(request("posts", "POST", { title: "Account A" }, `moon_editor_refresh=${refresh}`, { "X-MoonBlogger-Editor-Owner": owner(305) }), ["posts"]);
    expect(response.status).toBe(401);
    expect(fetched).not.toHaveBeenCalled();
  });
});
