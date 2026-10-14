import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const { getPublishedPostsPage } = vi.hoisted(() => ({
  getPublishedPostsPage: vi.fn(),
}));

vi.mock("@/lib/api", () => ({ getPublishedPostsPage, getPublicTags: async () => [], ApiError: class extends Error {} }));

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
import HomePage from "./page";

describe("HomePage", () => {
  beforeEach(() => {
    getPublishedPostsPage.mockReset();
  });

  it("shows the empty state only after a successful empty response", async () => {
    getPublishedPostsPage.mockResolvedValue({ results: [], next: null, previous: null });

    const markup = renderToStaticMarkup(await HomePage({}));

    expect(markup).toContain("Todavía no hay publicaciones");
    expect(markup).not.toContain("No se pudieron cargar las publicaciones");
  });

  it("propaga un fallo al boundary en vez de cachear una pantalla de error como éxito", async () => {
    getPublishedPostsPage.mockRejectedValue(new Error("API unavailable"));
    await expect(HomePage({})).rejects.toThrow("API unavailable");
  });
});

it("offers reset for malformed filters without requesting all posts", async () => {
  getPublishedPostsPage.mockClear();
  const markup = renderToStaticMarkup(await HomePage({ searchParams: Promise.resolve({ tag: "<bad>" }) }));
  expect(markup).toContain("No pudimos aplicar estos filtros");
  expect(markup).toContain('href="/"');
  expect(getPublishedPostsPage).not.toHaveBeenCalled();
});
