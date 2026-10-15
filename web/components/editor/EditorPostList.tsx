"use client";
import Link from "next/link";
import {useEffect, useState} from "react";
import {useEditorSession} from "./EditorSession";
import {EditorRequestError, type PrivatePost} from "./model";
import styles from "./Editor.module.css";
interface Page {count:number;next:string|null;previous:string|null;results:PrivatePost[];}
export default function EditorPostList() {
  const {request} = useEditorSession();
  const [status,setStatus] = useState(""); const [page,setPage] = useState(1); const [retry,setRetry]=useState(0);
  const [data,setData]=useState<Page|null>(null); const [error,setError]=useState(""); const [loading,setLoading]=useState(true);
  useEffect(()=>{let active=true; void request<Page>(`posts?page=${page}${status ? `&status=${status}` : ""}`).then(value=>{if(active)setData(value);}).catch(error=>{if(active)setError(error instanceof EditorRequestError?error.message:"No se pudo cargar el listado.");}).finally(()=>{if(active)setLoading(false);}); return()=>{active=false;};},[request,status,page,retry]);
  return <section aria-labelledby="editor-list-title">
    <div className={styles.panel}><div className={styles.row}><div><h1 id="editor-list-title">Tus publicaciones</h1><p className={styles.muted}>Un lugar para tus ideas, recuerdos y pequeños momentos.</p></div><Link className={styles.primary} href="/editor/new">Nueva publicación</Link></div>
    <div className={styles.listFilters} role="group" aria-label="Estado de las publicaciones">{[["","Todas"],["draft","Borradores"],["published","Publicadas"]].map(([value,label])=><button type="button" key={value} className={styles.chip} aria-pressed={status===value} onClick={()=>{if(value===status)return;setLoading(true);setError("");setStatus(value);setPage(1);}}>{label}</button>)}</div></div>
    {loading?<p role="status">Cargando publicaciones…</p>:error?<div className={styles.notice}><p role="alert" className={styles.error}>{error}</p><button className={styles.secondary} onClick={()=>{setLoading(true);setError("");setRetry(value=>value+1);}}>Reintentar</button></div>:data&&<>
      <p role="status" className={styles.muted}>{data.count} {data.count===1?"publicación":"publicaciones"}</p>
      {data.results.length===0?<div className={styles.panel}><h2>Aquí comienza tu próxima historia</h2><p>Todavía no hay publicaciones en esta sección.</p></div>:<ul className={styles.list}>{data.results.map(post=><li key={post.id} className={styles.listItem}><div className={styles.row}><h2>{post.title}</h2><span className={styles.badge}>{post.status==="published"?"Publicada":"Borrador"}</span></div><p className={styles.muted}>Actualizada el {new Intl.DateTimeFormat("es-MX",{dateStyle:"medium"}).format(new Date(post.updated_at))}{post.tags?.length>0?` · ${post.tags.map(tag=>`#${tag.name}`).join(" ")}`:""}</p><div className={styles.row}><Link className={styles.secondary} href={`/editor/${post.id}`}>Editar publicación</Link>{post.status==="published"&&<Link className={styles.textLink} href={`/posts/${post.slug}`}>Ver en el blog</Link>}</div></li>)}</ul>}
      {(data.next||data.previous)&&<nav className={styles.pagination} aria-label="Paginación privada"><button className={styles.secondary} disabled={!data.previous} onClick={()=>{setLoading(true);setError("");setPage(value=>value-1);}}>Anterior</button><span>Página {page}</span><button className={styles.secondary} disabled={!data.next} onClick={()=>{setLoading(true);setError("");setPage(value=>value+1);}}>Siguiente</button></nav>}
    </>}
  </section>;
}
