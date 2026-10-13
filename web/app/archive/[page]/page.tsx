import { notFound, redirect } from "next/navigation";
import PostFeed from "@/components/PostFeed";

export const revalidate = 3600;
export const dynamicParams = true;

// Páginas antiguas se generan y cachean al visitarlas, sin recorrer la API en build.
export function generateStaticParams() { return []; }

export default async function ArchivePage({ params }: {
  params: Promise<{ page: string }>;
}) {
  const { page: value } = await params;
  if (!/^[1-9]\d*$/.test(value)) notFound();
  const page = Number(value);
  if (!Number.isSafeInteger(page)) notFound();
  if (page === 1) redirect("/");
  return PostFeed({ page });
}
