import Link from "next/link";
import styles from "./Header.module.css";
import ThemeToggle from "./ThemeToggle";
import BrandArtwork from "./BrandArtwork";

export default function Header() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <BrandArtwork className={styles.trailMarker} />
        <Link href="/" className={styles.brand}>
          MoonBlogger
        </Link>
        <div className={styles.controls}>
          <Link href="/editor" className={styles.writeLink}>Escribir</Link>
          <ThemeToggle />
        </div>
      </div>
    </header>
  );
}
