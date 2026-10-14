import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError } from "@/lib/api";
import TagExplorer from "./TagExplorer";
import { getPublishedPostsPage, getPublicTags } from "@/lib/api";
import PostCard from "@/components/PostCard";
import BrandArtwork from "@/components/BrandArtwork";
import RouteState from "@/components/RouteState";
import styles from "@/app/page.module.css";

export default async function PostFeed({ page, tags = [], home = false }: { page: number; tags?: string[]; home?: boolean }) {
  let data;
  try {
    data = await (home ? getPublishedPostsPage(page, tags) : getPublishedPostsPage(page));
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }
  const catalog = home ? await getPublicTags() : [];
  const posts = data.results;
  const HeroHeading = posts.length === 0 && !home ? "p" : "h1";

  return (
    <>
      <section className={styles.hero}>
        <div className={styles.heroContent}>
          <HeroHeading className={styles.heroTitle}>MoonBlogger</HeroHeading>
          <p className={styles.heroText}>
            “¿Qué imagen haces de ti misma con los pedazos que recoges de tu sombra?”
          </p>
        </div>
        <BrandArtwork className={styles.heroScene} />
      </section>

      {home ? <TagExplorer catalog={catalog} initialData={data} initialTags={tags} initialPage={page} /> : posts.length === 0 ? (
        <RouteState
          title="Todavía no hay publicaciones"
          description="Vuelve pronto para encontrar novedades."
          variant="empty"
        />
      ) : (
        <section className={styles.feed} aria-labelledby="publicaciones-title">
          <h2 id="publicaciones-title" className={styles.feedTitle}>Publicaciones</h2>
          <ul className={styles.list}>
            {posts.map((post) => (
              <PostCard key={post.id} post={post} />
            ))}
          </ul>
          <nav className={styles.pagination} aria-label="Páginas de publicaciones">
            {data.previous ? <Link href={page === 2 ? "/" : `/archive/${page - 1}`} prefetch={false}>Publicaciones más recientes</Link> : null}
            <span aria-current="page">Página {page}</span>
            {data.next ? <Link href={`/archive/${page + 1}`} prefetch={false}>Publicaciones anteriores</Link> : null}
          </nav>
        </section>
      )}
    </>
  );
}
