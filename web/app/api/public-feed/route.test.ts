import { describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
const { getPublishedPostsPage } = vi.hoisted(() => ({ getPublishedPostsPage: vi.fn() }));
vi.mock("@/lib/api", () => ({ getPublishedPostsPage, ApiError: class extends Error { status = 503; } }));
import { GET } from "./route";
it("returns the backend OR total without re-filtering a page", async () => {
  getPublishedPostsPage.mockResolvedValue({ count: 23, results: [{ id: 1 }], next: "api-page-2", previous: null });
  const response = await GET(new NextRequest("http://localhost/api/public-feed?tag=viajes&tag=dan&page=2"));
  expect(getPublishedPostsPage).toHaveBeenLastCalledWith(2, ["dan", "viajes"]);
  expect((await response.json()).count).toBe(23);
});
describe("failures", () => {
  it("returns an explicit service error so the client can retain old results", async () => {
    getPublishedPostsPage.mockRejectedValue(new Error("database password must not leak"));
    const response = await GET(new NextRequest("http://localhost/api/public-feed"));
    expect(response.status).toBe(503);
    expect(await response.text()).not.toContain("password");
  });
  it("rejects unknown parameters and excessive filter input before calling Django", async () => {
    getPublishedPostsPage.mockClear();
    expect((await GET(new NextRequest("http://localhost/api/public-feed?status=draft"))).status).toBe(400);
    expect((await GET(new NextRequest(`http://localhost/api/public-feed?tag=${"a".repeat(1000)}`))).status).toBe(400);
    expect(getPublishedPostsPage).not.toHaveBeenCalled();
  });
});
