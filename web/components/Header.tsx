import Link from "next/link";
import styles from "./Header.module.css";
import KotoSprite from "./KotoSprite";

/**
 * Cabecera del sitio.
 *
 * Reserva un slot para la mascota sprite (pixel art futuro). Hoy está vacío:
 * un recuadro sutil que mantiene el espacio sin romper el layout.
 */
export default function Header() {
  return (
    <header className={styles.header}>
      <div className={styles.inner}>
        <div className={styles.mascotSlot}><KotoSprite size="sm" /></div>
        <Link href="/" className={styles.brand}>
          MoonBlogger
        </Link>
      </div>
    </header>
  );
}
