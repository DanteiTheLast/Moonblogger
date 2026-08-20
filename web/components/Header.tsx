import Link from "next/link";
import styles from "./Header.module.css";
import ThemeToggle from "./ThemeToggle";

export default function Header() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <div className={styles.trailMarker} aria-hidden="true" />
        <Link href="/" className={styles.brand}>
          MoonBlogger
        </Link>
        <ThemeToggle />
      </div>
    </header>
  );
}
