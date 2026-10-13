import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
const { getPublishedPostsPage } = vi.hoisted(() => ({ getPublishedPostsPage: vi.fn() }));
vi.mock("@/lib/api", () => ({ getPublishedPostsPage, ApiError: class extends Error {} }));
import PostFeed from "./PostFeed";
const post = { id: 21, slug: "older-post", title: "Antigua", content: "Texto", published_at: "2026-10-01T00:00:00Z", created_at: "2026-10-01T00:00:00Z", cover: null };

describe("navegación del archivo", () => {
  beforeEach(() => getPublishedPostsPage.mockReset());
  it("hace accesible la página posterior a las primeras veinte publicaciones", async () => {
    getPublishedPostsPage.mockResolvedValue({ results: [post], next: "api-page-3", previous: "api-page-1" });
    const markup = renderToStaticMarkup(await PostFeed({ page: 2 }));
    expect(getPublishedPostsPage).toHaveBeenCalledWith(2);
    expect(markup).toContain('href="/posts/older-post"');
    expect(markup).toContain('href="/archive/3"');
    expect(markup).toContain('href="/"');
    expect(markup).toContain("Página 2");
  });
  it("no ofrece enlaces fuera del archivo en sus extremos", async () => {
    getPublishedPostsPage.mockResolvedValue({ results: [post], next: null, previous: null });
    const markup = renderToStaticMarkup(await PostFeed({ page: 1 }));
    expect(markup).not.toContain("Publicaciones anteriores");
    expect(markup).not.toContain("Publicaciones más recientes");
  });
});
