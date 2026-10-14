export type FilterQuery = { tags: string[]; page: number };
export function parseFilterQuery(params: URLSearchParams): FilterQuery {
  const tags = [...new Set(params.getAll("tag").map((value) => value.trim().toLowerCase()))].filter((value) => /^[a-z][a-z0-9-]{0,49}$/.test(value)).sort();
  const rawPage = params.get("page") ?? "1";
  const page = /^\d+$/.test(rawPage) && Number.isSafeInteger(Number(rawPage)) && Number(rawPage) > 0 ? Number(rawPage) : 1;
  return { tags, page };
}
export function filterHref(tags: string[], page = 1): string {
  const params = new URLSearchParams();
  [...new Set(tags)].sort().forEach((tag) => params.append("tag", tag));
  if (page > 1) params.set("page", String(page));
  return params.size ? `/?${params}` : "/";
}

export function validFilterQuery(params: URLSearchParams): boolean {
  return params.toString().length <= 600 && [...params.keys()].every((key) => ["tag", "page"].includes(key)) && params.getAll("tag").length <= 9 && params.getAll("page").length <= 1 && params.getAll("tag").every((tag) => /^[a-z][a-z0-9-]{0,49}$/.test(tag)) && (!params.has("page") || /^[1-9][0-9]{0,8}$/.test(params.get("page")!));
}
