import type { Metadata } from "next";
import Link from "next/link";
import { ApiError } from "@/lib/api";
import { Suspense } from "react";
import PostFeed from "@/components/PostFeed";
import { parseFilterQuery, validFilterQuery } from "@/lib/tagFilters";
type Props = { searchParams?: Promise<Record<string, string | string[] | undefined>> };
async function filters(props: Props) {
  const raw = await props.searchParams ?? {};
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(raw)) for (const item of Array.isArray(value) ? value : value ? [value] : []) params.append(key, item);
  return { ...parseFilterQuery(params), valid: validFilterQuery(params) };
}
export async function generateMetadata(props: Props): Promise<Metadata> {
  const { tags, valid } = await filters(props);
  return { title: "MoonBlogger", description: "El blog público de Moon: sus publicaciones, en orden.", ...(tags.length || !valid ? { robots: { index: false, follow: true } } : {}), alternates: { canonical: "/" } };
}
export const revalidate = 3600;
export default async function HomePage(props: Props) {
  const { tags, page, valid } = await filters(props);
  if (!valid) return invalidFilters();
  let feed;
  let invalid = false;
  try {
    feed = await PostFeed({ page, tags, home: true });
  } catch (error) {
    if (error instanceof ApiError && error.status === 400) invalid = true;
    else throw error;
  }
  return invalid ? invalidFilters() : <Suspense fallback={<p>Cargando publicaciones…</p>}>{feed}</Suspense>;
}
function invalidFilters() {
  return <section role="alert"><h1>No pudimos aplicar estos filtros</h1><p>La selección no es válida. Puedes volver a todas las publicaciones.</p><Link href="/">Ver todas</Link></section>;
}
