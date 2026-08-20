import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { getPublishedPosts } = vi.hoisted(() => ({
  getPublishedPosts: vi.fn(),
}));

vi.mock("@/lib/api", () => ({ getPublishedPosts }));

import HomePage from "./page";

describe("HomePage", () => {
  beforeEach(() => {
    getPublishedPosts.mockReset();
  });

  it("shows the empty state only after a successful empty response", async () => {
    getPublishedPosts.mockResolvedValue([]);

    const markup = renderToStaticMarkup(await HomePage());

    expect(markup).toContain("Todavía no hay publicaciones");
    expect(markup).not.toContain("No se pudieron cargar las publicaciones");
  });

  it("distinguishes an API failure and offers a server-compatible retry link", async () => {
    getPublishedPosts.mockRejectedValue(new Error("API unavailable"));

    const markup = renderToStaticMarkup(await HomePage());

    expect(markup).toContain("No se pudieron cargar las publicaciones");
    expect(markup).toContain('href="/"');
    expect(markup).toContain("Reintentar");
    expect(markup).not.toContain("Todavía no hay publicaciones");
  });
});
