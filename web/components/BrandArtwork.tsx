import styles from "./BrandArtwork.module.css";

/** Decorative artwork: the browser chooses a still image when motion is reduced. */
export default function BrandArtwork({ className = "" }: { className?: string }) {
  return (
    <picture className={`${styles.artwork} ${className}`}>
      <source media="(prefers-reduced-motion: reduce)" srcSet="/brand/koto-static.png" />
      <source type="image/webp" srcSet="/brand/koto-animated.webp" />
      {/* Keep the supplied animation intact instead of passing it through image optimization. */}
      <img src="/brand/koto-static.png" width={512} height={512} alt="" aria-hidden="true" />
    </picture>
  );
}
