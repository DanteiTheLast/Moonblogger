"use client";

import Link from "next/link";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import BrandArtwork from "@/components/BrandArtwork";
import { editorRequest, EditorRequestError } from "./model";
import styles from "./Editor.module.css";

type Session = { authenticated: boolean; ownerKey: string | null };
type Request = <T>(path: string, options?: RequestInit) => Promise<T>;
const Context = createContext<{ownerKey: string; request: Request} | null>(null);
// One request at a time prevents simultaneous refreshes from rotating the same cookie.
let pending: Promise<unknown> = Promise.resolve();
function queued<T>(path: string, options?: RequestInit): Promise<T> {
  const next = pending.then(async (): Promise<T> => {
    if (typeof navigator !== "undefined" && navigator.locks?.request) return await navigator.locks.request("moonblogger-editor-session", () => editorRequest<T>(path,options));
    return editorRequest<T>(path,options);
  });
  pending = next.catch(() => undefined);
  return next;
}
export function useEditorSession() {
  const context = useContext(Context);
  if (!context) throw new Error("Editor session is unavailable");
  return context;
}
export default function EditorSession({ children }: {children: ReactNode}) {
  const [session, setSession] = useState<Session | null>(null);
  const [owner, setOwner] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const load = useCallback(async () => {
    setError("");
    try { const next = await queued<Session>("session"); setSession(next); if (next.authenticated && next.ownerKey) setOwner(next.ownerKey); }
    catch { setError("No se pudo comprobar la sesión. Reintenta cuando tengas conexión."); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  const request = useCallback<Request>(async <T,>(path: string, options?: RequestInit) => {
    try { return await queued<T>(path,{...options,headers:{...options?.headers,"X-MoonBlogger-Editor-Owner":owner ?? ""}}); }
    catch (error) { if (error instanceof EditorRequestError && error.status === 401) setSession({authenticated:false,ownerKey:null}); throw error; }
  }, [owner]);
  async function login(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError("");
    try {
      await queued("session", {method:"POST",body:JSON.stringify({username:username.trim(),password})});
      const next = await queued<Session>("session");
      if (!next.authenticated || !next.ownerKey) throw new Error();
      setPassword(""); setSession(next); setOwner(next.ownerKey);
    } catch (error) { setPassword(""); setError(error instanceof EditorRequestError && error.status === 429 ? error.message : "No se pudo entrar. Revisa tu usuario y contraseña o inténtalo de nuevo."); }
    finally { setBusy(false); }
  }
  async function logout() {
    if (!window.confirm("¿Cerrar la sesión? Las copias locales de los escritos se conservan en este navegador para tu cuenta.")) return;
    setBusy(true); setError("");
    try { await queued("session", {method:"DELETE"}); setOwner(null); setSession({authenticated:false,ownerKey:null}); }
    catch { setError("No se pudo cerrar la sesión. Reintenta."); }
    finally { setBusy(false); }
  }
  if (session === null) return <section className={styles.panel}><h1>Tu espacio para escribir</h1><p role="status">Comprobando sesión…</p>{error && <><p role="alert">{error}</p><button className={styles.secondary} onClick={load}>Reintentar</button></>}</section>;
  return <>
    {!session.authenticated && <section className={`${styles.panel} ${styles.login}`} aria-labelledby="login-title">
      <BrandArtwork className={styles.loginArt}/>
      <h1 id="login-title">Tu espacio, Moon</h1>
      {owner && <p>Tu sesión terminó. Vuelve a entrar y continúa con el escrito que tienes abierto.</p>}
      <form className={styles.stack} onSubmit={login}>
        <label className={styles.field}>Usuario<input name="username" autoComplete="username" required autoFocus value={username} onChange={event=>setUsername(event.target.value)} disabled={busy}/></label>
        <label className={styles.field}>Contraseña<input name="password" type="password" autoComplete="current-password" required value={password} onChange={event=>setPassword(event.target.value)} disabled={busy}/></label>
        {error && <p className={styles.error} role="alert">{error}</p>}
        <button className={styles.primary} disabled={busy}>{busy ? "Entrando…" : "Entrar"}</button>
      </form><Link className={styles.textLink} href="/">Volver al blog</Link>
    </section>}
    {owner && <Context.Provider key={owner} value={{ownerKey:owner, request}}><div hidden={!session.authenticated}>
      <nav className={styles.sessionBar} aria-label="Espacio privado"><Link href="/editor">Mis publicaciones</Link><button className={styles.smallButton} type="button" disabled={busy} onClick={logout}>Cerrar sesión</button></nav>
      {error && session.authenticated && <p role="alert" className={styles.error}>{error}</p>}
      {children}
    </div></Context.Provider>}
  </>;
}
