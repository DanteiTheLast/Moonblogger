import { describe, expect, it } from "vitest";
import { filterHref, parseFilterQuery } from "./tagFilters";
describe("shared homepage filters", () => {
  it("normalizes repeated slugs and resets invalid page values", () => {
    expect(parseFilterQuery(new URLSearchParams("tag=Viajes&tag=dan&tag=viajes&tag=<script>&page=-2"))).toEqual({ tags: ["dan", "viajes"], page: 1 });
  });
  it("preserves OR parameters while paginating on the homepage", () => {
    expect(filterHref(["viajes", "dan", "dan"], 2)).toBe("/?tag=dan&tag=viajes&page=2");
    expect(filterHref([], 1)).toBe("/");
  });
});

import { validFilterQuery } from "./tagFilters";
it("rejects invalid homepage filters instead of silently showing everything", () => {
  expect(validFilterQuery(new URLSearchParams("tag=<script>"))).toBe(false);
  expect(validFilterQuery(new URLSearchParams("status=draft"))).toBe(false);
  expect(validFilterQuery(new URLSearchParams("tag=dan&tag=viajes&page=2"))).toBe(true);
});
