import styles from "./PixelScene.module.css";

export type PixelSceneVariant = "hero" | "empty" | "error" | "notFound" | "quiet";

interface PixelSceneProps {
  variant?: PixelSceneVariant;
  className?: string;
}

/** Decorative-only CSS scene; route content supplies all meaningful context. */
export default function PixelScene({ variant = "hero", className }: PixelSceneProps) {
  const sceneClassName = [styles.scene, styles[variant], className]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={sceneClassName} aria-hidden="true">
      <div className={styles.moon} />
      <div className={styles.starField}>
        <i className={styles.star} />
        <i className={styles.star} />
        <i className={styles.star} />
        <i className={styles.star} />
      </div>
      <div className={styles.clouds}>
        <i className={styles.cloud} />
        <i className={styles.cloud} />
      </div>
      <div className={styles.bunting}>
        <i />
        <i />
        <i />
      </div>
      <div className={styles.mountainBack} />
      <div className={styles.mountainFront} />
      <div className={styles.ground} />
    </div>
  );
}
