import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Post } from "@/lib/types";

const { getPublishedPostBySlug, getPublishedPosts } = vi.hoisted(() => ({
  getPublishedPostBySlug: vi.fn(),
  getPublishedPosts: vi.fn(),
}));

vi.mock("@/lib/api", () => ({ getPublishedPostBySlug, getPublishedPosts }));

import PostPage from "./page";

const POST: Post = {
  id: 1,
  slug: "noche-rosa",
  title: "Una noche rosa",
  content: "Primera línea\nSegunda línea",
  status: "published",
  created_at: "2026-08-10T12:00:00Z",
  updated_at: "2026-08-10T12:00:00Z",
  published_at: "2026-08-11T12:00:00Z",
  carousel_transition: "fade",
  cover: null,
  media_count: 0,
  media: [],
};

describe("PostPage SSR", () => {
  beforeEach(() => {
    getPublishedPostBySlug.mockReset();
    getPublishedPosts.mockReset();
  });

  it("renders the editorial header, return link, date and plain post text", async () => {
    getPublishedPostBySlug.mockResolvedValue(POST);

    const markup = renderToStaticMarkup(
      await PostPage({ params: Promise.resolve({ slug: POST.slug }) }),
    );

    expect(getPublishedPostBySlug).toHaveBeenCalledWith(POST.slug);
    expect(markup).toContain('href="/"');
    expect(markup).toContain("Volver al listado");
    expect(markup).toContain("Una noche rosa");
    expect(markup).toContain("11 de agosto de 2026");
    expect(markup).toContain("Primera línea\nSegunda línea");
  });
});
