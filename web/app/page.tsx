import type { Metadata } from "next";
import { getPublishedPosts } from "@/lib/api";
import PostCard from "@/components/PostCard";
import PixelScene from "@/components/PixelScene";
import RouteState from "@/components/RouteState";
import styles from "./page.module.css";

export const metadata: Metadata = {
  title: "MoonBlogger",
  description: "El blog público de Moon: sus publicaciones, en orden.",
};

export const revalidate = 3600;

export default async function HomePage() {
  let posts: Awaited<ReturnType<typeof getPublishedPosts>> = [];
  let hasError = false;

  try {
    posts = await getPublishedPosts();
  } catch {
    hasError = true;
  }

  const HeroHeading = hasError || posts.length === 0 ? "p" : "h1";

  return (
    <>
      <section className={styles.hero}>
        <div className={styles.heroContent}>
          <HeroHeading className={styles.heroTitle}>MoonBlogger</HeroHeading>
          <p className={styles.heroText}>
            Hola, soy Moon. Aquí guardo mis publicaciones.
          </p>
        </div>
        <PixelScene className={styles.heroScene} variant="hero" />
      </section>

      {hasError ? (
        <RouteState
          title="No se pudieron cargar las publicaciones"
          description="Comprueba tu conexión e inténtalo de nuevo."
          variant="error"
          action={{ label: "Reintentar", href: "/" }}
        />
      ) : posts.length === 0 ? (
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
        </section>
      )}
    </>
  );
}
