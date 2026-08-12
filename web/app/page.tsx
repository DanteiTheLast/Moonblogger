import type { Metadata } from "next";
import { getPublishedPosts } from "@/lib/api";
import PostCard from "@/components/PostCard";
import styles from "./page.module.css";

export const metadata: Metadata = {
  title: "MoonBlogger",
  description: "El blog público de Moon: sus publicaciones, en orden.",
};

export default async function HomePage() {
  const posts = await getPublishedPosts();

  return (
    <>
      <section className={styles.hero}>
        <h1 className={styles.heroTitle}>MoonBlogger</h1>
        <p className={styles.heroText}>
          Hola, soy Moon. Aquí guardo mis publicaciones.
        </p>
      </section>

      {posts.length === 0 ? (
        <p className={styles.empty} role="status">
          Todavía no hay publicaciones.
        </p>
      ) : (
        <ul className={styles.list}>
          {posts.map((post) => (
            <PostCard key={post.id} post={post} />
          ))}
        </ul>
      )}
    </>
  );
}
