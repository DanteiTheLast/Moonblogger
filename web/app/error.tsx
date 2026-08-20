"use client";

import { useEffect } from "react";
import RouteState from "@/components/RouteState";

interface ErrorPageProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function ErrorPage({ error, reset }: ErrorPageProps) {
  useEffect(() => {
    console.error("MoonBlogger web:", error);
  }, [error]);

  return (
    <RouteState
      variant="error"
      title="Algo salió mal"
      description="No pudimos cargar las publicaciones. Inténtalo de nuevo en un momento."
      announceError
      action={{ label: "Reintentar", onClick: reset }}
    />
  );
}
