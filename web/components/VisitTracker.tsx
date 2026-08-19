"use client";

import { useEffect, useRef } from "react";
import { usePathname } from "next/navigation";

const trackable = (path: string) => path === "/" || /^\/posts\/[^/]+$/.test(path);

export default function VisitTracker() {
  const pathname = usePathname();
  const sent = useRef(new Set<string>());
  useEffect(() => {
    if (!trackable(pathname) || sent.current.has(pathname)) return;
    sent.current.add(pathname);
    const body = JSON.stringify({ path: pathname });
    const blob = new Blob([body], { type: "application/json" });
    if (navigator.sendBeacon) navigator.sendBeacon("/api/visit", blob);
    else void fetch("/api/visit", { method: "POST", body, headers: { "Content-Type": "application/json" }, keepalive: true });
  }, [pathname]);
  return null;
}
