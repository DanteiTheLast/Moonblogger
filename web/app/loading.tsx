import styles from "./loading.module.css";
import KotoSprite from "@/components/KotoSprite";

export default function Loading() {
  return (
    <div className={styles.wrap} aria-busy="true" aria-label="Cargando">
      <div className={styles.panel}><KotoSprite variant="sleeping" size="lg" /><strong>CARGANDO MUNDO...</strong></div>
      <div className={styles.skeleton} />
      <div className={styles.skeleton} />
      <div className={styles.skeleton} />
      <span className={styles.srOnly}>Cargando…</span>
    </div>
  );
}
