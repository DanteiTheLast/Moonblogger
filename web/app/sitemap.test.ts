import { beforeEach, describe, expect, it, vi } from "vitest";
const { getAllPublishedPosts } = vi.hoisted(() => ({ getAllPublishedPosts: vi.fn() }));
vi.mock("@/lib/api", () => ({ getAllPublishedPosts, getApiBaseUrl: () => "http://api.test/api/v1" }));
import sitemap from "./sitemap";

describe("sitemap completo", () => {
  beforeEach(() => { getAllPublishedPosts.mockReset(); process.env.SITE_URL = "https://moon.example/"; });
  it("incluye publicaciones antiguas del archivo y URLs públicas", async () => {
    getAllPublishedPosts.mockResolvedValue([{ slug: "antigua", published_at: "2026-01-01T00:00:00Z" }]);
    const entries = await sitemap();
    expect(entries.map(entry => entry.url)).toEqual(["https://moon.example", "https://moon.example/posts/antigua"]);
  });
  it("propaga el fallo para no reemplazar el sitemap útil con uno incompleto", async () => {
    getAllPublishedPosts.mockRejectedValue(new Error("API unavailable"));
    await expect(sitemap()).rejects.toThrow("API unavailable");
  });
});
