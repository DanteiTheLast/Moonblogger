"use client";

import { useEffect } from "react";
import styles from "./error.module.css";

interface ErrorPageProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function ErrorPage({ error, reset }: ErrorPageProps) {
  useEffect(() => {
    console.error("MoonBlogger web:", error);
  }, [error]);

  return (
    <div className={styles.wrap} role="alert">
      <h1 className={styles.title}>Algo salió mal</h1>
      <p className={styles.text}>
        No pudimos cargar las publicaciones. Inténtalo de nuevo en un momento.
      </p>
      <button type="button" onClick={reset} className={styles.button}>
        Reintentar
      </button>
    </div>
  );
}
