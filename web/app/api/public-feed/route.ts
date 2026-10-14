import { NextRequest, NextResponse } from "next/server";
import { ApiError, getPublishedPostsPage } from "@/lib/api";
import { parseFilterQuery, validFilterQuery } from "@/lib/tagFilters";
export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  if (!validFilterQuery(params)) {
    return NextResponse.json({ detail: "Filtros inválidos." }, { status: 400 });
  }
  const { tags, page } = parseFilterQuery(request.nextUrl.searchParams);
  try {
    return NextResponse.json(await getPublishedPostsPage(page, tags));
  } catch (error) {
    return NextResponse.json({ detail: "No se pudieron cargar las publicaciones." }, { status: error instanceof ApiError && error.status >= 400 && error.status < 500 ? error.status : 503 });
  }
}
