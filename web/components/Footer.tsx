import styles from "./Footer.module.css";
import Link from "next/link";

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <p className={styles.text}>MoonBlogger: el blog de Moon.</p>
      <Link href="/privacy">Privacidad</Link>
    </footer>
  );
}
