import Link from "next/link";
import styles from "./not-found.module.css";

export default function NotFound() {
  return (
    <div className={styles.wrap}>
      <h1 className={styles.title}>No encontramos esa publicación</h1>
      <p className={styles.text}>
        Puede que no exista o que todavía esté en borrador. Vuelve al listado
        para ver las publicaciones disponibles.
      </p>
      <Link href="/" className={styles.link}>
        Volver al listado
      </Link>
    </div>
  );
}
