import styles from "./loading.module.css";

export default function Loading() {
  return (
    <div className={styles.wrap} aria-busy="true" aria-label="Cargando">
      <div className={styles.skeleton} />
      <div className={styles.skeleton} />
      <div className={styles.skeleton} />
      <span className={styles.srOnly}>Cargando…</span>
    </div>
  );
}
