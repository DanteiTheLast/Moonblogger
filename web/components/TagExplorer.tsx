"use client";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { PostListResponse, Tag } from "@/lib/types";
import { filterHref, parseFilterQuery } from "@/lib/tagFilters";
import PostCard from "./PostCard";
import styles from "./TagExplorer.module.css";
import feedStyles from "@/app/page.module.css";

export default function TagExplorer({ catalog, initialData, initialTags, initialPage }: { catalog: Tag[]; initialData: PostListResponse; initialTags: string[]; initialPage: number }) {
  const search = useSearchParams();
  const query = parseFilterQuery(new URLSearchParams(search.toString()));
  const href = filterHref(query.tags, query.page);
  const [result, setResult] = useState({ data: initialData, tags: initialTags, page: initialPage });
  const [pendingTags, setPendingTags] = useState(initialTags);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [retry, setRetry] = useState(0);
  const completed = useRef(filterHref(initialTags, initialPage));
  const retrySeen = useRef(0);
  useEffect(() => {
    const controller = new AbortController();
    if (href === completed.current && retry === retrySeen.current) {
      Promise.resolve().then(() => {
        if (controller.signal.aborted) return;
        setLoading(false); setError(false);
        setPendingTags(parseFilterQuery(new URLSearchParams(href.split("?")[1] ?? "")).tags);
      });
      return () => controller.abort();
    }
    retrySeen.current = retry;
    // Begin asynchronously so the previous, complete results remain visible.
    Promise.resolve().then(async () => {
      if (controller.signal.aborted) return;
      setLoading(true); setError(false);
      const target = parseFilterQuery(new URLSearchParams(href.split("?")[1] ?? ""));
      setPendingTags(target.tags);
      try {
        const response = await fetch(`/api/public-feed${href.includes("?") ? href.slice(1) : ""}`, { signal: controller.signal });
        if (!response.ok) throw new Error("feed unavailable");
        const data = await response.json() as PostListResponse;
        if (controller.signal.aborted) return;
        setResult({ data, tags: target.tags, page: target.page });
        completed.current = href;
      } catch {
        if (!controller.signal.aborted) setError(true);
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    });
    return () => controller.abort();
  }, [href, retry]);
  function navigate(target: string) {
    if (target !== `${window.location.pathname}${window.location.search}`) window.history.pushState(null, "", target);
  }
  const names = result.tags.map((slug) => catalog.find((tag) => tag.slug === slug)?.name ?? slug);
  const changed = filterHref(pendingTags) !== filterHref(result.tags);
  return <>
    <section className={styles.panel} aria-labelledby="explora-title">
      <h2 id="explora-title">Explora por etiquetas</h2>
      <p id="filter-help">Elige una o varias. Encontrarás publicaciones con cualquiera de ellas.</p>
      {catalog.length ? <fieldset className={styles.options} aria-describedby="filter-help">
        <legend className={styles.visuallyHidden}>Etiquetas disponibles</legend>
        {catalog.map((tag) => <button type="button" key={tag.slug} aria-pressed={pendingTags.includes(tag.slug)} disabled={loading} onClick={() => setPendingTags((current) => current.includes(tag.slug) ? current.filter((slug) => slug !== tag.slug) : [...current, tag.slug].sort())}>{pendingTags.includes(tag.slug) ? "✓ " : ""}#{tag.name}</button>)}
      </fieldset> : <p>Aún no hay etiquetas en las publicaciones.</p>}
      <div className={styles.actions}>
        <button type="button" disabled={loading || !changed} onClick={() => navigate(filterHref(pendingTags))}>Aplicar filtros</button>
        <button type="button" disabled={loading || (!result.tags.length && !pendingTags.length && result.page === 1)} onClick={() => { setPendingTags([]); navigate("/"); }}>Ver todas</button>
      </div>
      <p className={styles.status} role="status">{loading ? "Cargando; se muestran los resultados anteriores." : changed ? "Tienes cambios sin aplicar." : ""}</p>
      {error ? <div role="alert"><p>No pudimos actualizar el listado. Conservamos los resultados anteriores.</p><button type="button" onClick={() => setRetry((value) => value + 1)}>Reintentar</button></div> : null}
    </section>
    <section className={feedStyles.feed} aria-labelledby="publicaciones-title" aria-busy={loading}>
      <h2 id="publicaciones-title" className={feedStyles.feedTitle}>Publicaciones</h2>
      <p role="status">{names.length ? `${names.join(" o ")} · ` : ""}{result.data.count} {result.data.count === 1 ? "publicación" : "publicaciones"}</p>
      {result.data.results.length ? <ul className={feedStyles.list}>{result.data.results.map((post) => <PostCard key={post.id} post={post} />)}</ul> : <div className={styles.empty}><h3>{result.tags.length ? "No hay publicaciones con estas etiquetas" : "Todavía no hay publicaciones"}</h3><p>{result.tags.length ? "Prueba otra etiqueta o vuelve a ver todas." : "Vuelve pronto para encontrar novedades."}</p>{result.tags.length ? <Link href="/" scroll={false} onNavigate={(event) => { event.preventDefault(); setPendingTags([]); navigate("/"); }}>Ver todas</Link> : null}</div>}
      <nav className={feedStyles.pagination} aria-label="Páginas de publicaciones">
        {result.data.previous ? <Link href={filterHref(result.tags, result.page - 1)} onNavigate={(event) => { event.preventDefault(); navigate(filterHref(result.tags, result.page - 1)); }} scroll={false} prefetch={false}>Publicaciones más recientes</Link> : null}
        <span aria-current="page">Página {result.page}</span>
        {result.data.next ? <Link href={filterHref(result.tags, result.page + 1)} onNavigate={(event) => { event.preventDefault(); navigate(filterHref(result.tags, result.page + 1)); }} scroll={false} prefetch={false}>Publicaciones anteriores</Link> : null}
      </nav>
    </section>
  </>;
}
