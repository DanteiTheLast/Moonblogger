/* eslint-disable @next/next/no-img-element -- SVG decorativo local, sin optimización necesaria. */
import styles from "./KotoSprite.module.css";

type KotoSpriteProps = { variant?: "sitting" | "sleeping"; size?: "sm" | "md" | "lg" };

export default function KotoSprite({ variant = "sitting", size = "md" }: KotoSpriteProps) {
  return <img className={`${styles.sprite} ${styles[size]}`} src={`/illustrations/koto-${variant}.svg`} alt="" aria-hidden="true" />;
}
