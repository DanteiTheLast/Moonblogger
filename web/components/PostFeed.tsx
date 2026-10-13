import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError } from "@/lib/api";
import { getPublishedPostsPage } from "@/lib/api";
import PostCard from "@/components/PostCard";
import BrandArtwork from "@/components/BrandArtwork";
import RouteState from "@/components/RouteState";
import styles from "@/app/page.module.css";

export default async function PostFeed({ page }: { page: number }) {
  let data;
  try {
    data = await getPublishedPostsPage(page);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }
  const posts = data.results;
  const HeroHeading = posts.length === 0 ? "p" : "h1";

  return (
    <>
      <section className={styles.hero}>
        <div className={styles.heroContent}>
          <HeroHeading className={styles.heroTitle}>MoonBlogger</HeroHeading>
          <p className={styles.heroText}>
            Hola, soy Moon. Aquí guardo mis publicaciones.
          </p>
        </div>
        <BrandArtwork className={styles.heroScene} />
      </section>

      {posts.length === 0 ? (
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
