import styles from "./Footer.module.css";
import Link from "next/link";

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={styles.horizon} aria-hidden="true" />
      <div className={styles.inner}>
        <p className={styles.text}>MoonBlogger: el blog de Moon.</p>
        <Link className={styles.privacy} href="/privacy">Privacidad</Link>
      </div>
    </footer>
  );
}
