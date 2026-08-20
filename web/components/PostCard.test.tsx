import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { PostListItem } from "@/lib/types";
import PostCard from "./PostCard";

const postWithoutCover: PostListItem = {
  id: 1,
  slug: "sin-portada",
  title: "Una publicación sin portada",
  content: "Contenido de prueba.",
  status: "published",
  created_at: "2026-08-12T00:00:00Z",
  updated_at: "2026-08-12T00:00:00Z",
  published_at: "2026-08-12T00:00:00Z",
  carousel_transition: "slide",
  cover: null,
  media_count: 0,
};

describe("PostCard", () => {
  it("renders a decorative fallback and a visible link when the post has no cover", () => {
    const markup = renderToStaticMarkup(<PostCard post={postWithoutCover} />);

    expect(markup).toContain('aria-hidden="true"');
    expect(markup).not.toContain("Sin portada disponible");
    expect(markup).toContain('href="/posts/sin-portada"');
    expect(markup).toContain("Leer publicación");
    expect((markup.match(/href="\/posts\/sin-portada"/g) ?? [])).toHaveLength(1);
  });
});
