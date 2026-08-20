"use client";

import { useEffect, useState } from "react";
import styles from "./ThemeToggle.module.css";

type Theme = "light" | "dark";

const storageKey = "moonblogger-theme";

function systemTheme(): Theme {
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export default function ThemeToggle() {
  const [theme, setTheme] = useState<Theme | null>(null);

  useEffect(() => {
    let storedTheme: string | null = null;
    try {
      storedTheme = window.localStorage.getItem(storageKey);
    } catch {
      // The system preference remains available when storage is blocked.
    }
    const initialTheme: Theme = storedTheme === "dark" || storedTheme === "light" ? storedTheme : systemTheme();

    document.documentElement.dataset.theme = initialTheme;
    const frame = window.requestAnimationFrame(() => setTheme(initialTheme));
    return () => window.cancelAnimationFrame(frame);
  }, []);

  function toggleTheme() {
    const documentTheme = document.documentElement.dataset.theme;
    const currentTheme: Theme = theme ?? (documentTheme === "dark" || documentTheme === "light" ? documentTheme : systemTheme());
    const nextTheme: Theme = currentTheme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = nextTheme;
    try {
      window.localStorage.setItem(storageKey, nextTheme);
    } catch {
      // Keep the active session theme if browser storage is unavailable.
    }
    setTheme(nextTheme);
  }

  const isDark = theme === "dark";
  const hasTheme = theme !== null;

  return (
    <button
      type="button"
      className={styles.toggle}
      onClick={toggleTheme}
      aria-pressed={hasTheme ? isDark : undefined}
      aria-label={hasTheme ? `Cambiar a tema ${isDark ? "claro" : "oscuro"}` : "Cambiar tema"}
    >
      {hasTheme ? `Tema: ${isDark ? "oscuro" : "claro"}` : "Tema"}
    </button>
  );
}
