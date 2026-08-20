"use client";

import Link from "next/link";
import PixelScene from "./PixelScene";
import styles from "./RouteState.module.css";

export type RouteStateVariant = "empty" | "error" | "notFound" | "loading";

type RouteAction =
  | { label: string; href: string; onClick?: never }
  | { label: string; onClick: () => void; href?: never };

function isLinkAction(action: RouteAction): action is Extract<RouteAction, { href: string }> {
  return typeof action.href === "string";
}

interface RouteStateProps {
  title: string;
  description: string;
  variant: RouteStateVariant;
  action?: RouteAction;
  /** Set only when a client-side error replaces already rendered route content. */
  announceError?: boolean;
}

const sceneByVariant = {
  empty: "empty",
  error: "error",
  notFound: "notFound",
  loading: "quiet",
} as const;

export default function RouteState({
  title,
  description,
  variant,
  action,
  announceError = false,
}: RouteStateProps) {
  const isLoading = variant === "loading";
  const descriptionRole = variant === "error" && announceError ? "alert" : undefined;

  return (
    <section className={styles.state} aria-busy={isLoading || undefined}>
      <PixelScene className={styles.scene} variant={sceneByVariant[variant]} />
      <div className={styles.content}>
        <h1 className={styles.title}>{title}</h1>
        <p className={styles.description} role={descriptionRole}>{description}</p>
        {action && (isLinkAction(action) ? (
          <Link className={styles.action} href={action.href}>{action.label}</Link>
        ) : (
          <button className={styles.action} type="button" onClick={action.onClick}>{action.label}</button>
        ))}
      </div>
    </section>
  );
}
