"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import type { Tag } from "@/lib/types";
import { prepareEditorPhoto } from "@/lib/editorMedia";
import { useEditorSession } from "./EditorSession";
import {blankValues,draftKey,EditorRequestError,ensureCover,parseDraft,snapshot,valuesFromPost,type Draft,type EditorMedia,type EditorStatus,type EditorValues,type PrivatePost} from "./model";
import {saveEditorPost,UncertainCreationError} from "./savePost";
import styles from "./Editor.module.css";

type ReadURLs={media:{id:string;url:string;poster_url?:string|null}[]};
export default function PostEditor({postId}:{postId:number|null}) {
  const {ownerKey,request}=useEditorSession();
  const [values,setValues]=useState<EditorValues>(blankValues);
  const current=useRef(values);
  const [loading,setLoading]=useState(true);const [loadError,setLoadError]=useState("");const [reload,setReload]=useState(0);
  const [catalog,setCatalog]=useState<Tag[]>([]);const [tagsLoading,setTagsLoading]=useState(true);const [tagsError,setTagsError]=useState("");const [tagsReload,setTagsReload]=useState(0);
  const [dirty,setDirty]=useState(false);const [saving,setSaving]=useState(false);const [inspecting,setInspecting]=useState(false);
  const [error,setError]=useState("");const [notice,setNotice]=useState("");const [progress,setProgress]=useState("");
  const [recovery,setRecovery]=useState<Draft|null>(null);const [corrupt,setCorrupt]=useState(false);const [localError,setLocalError]=useState("");const [missingPhotos,setMissingPhotos]=useState(false);const [uncertain,setUncertain]=useState(false);
  const [savedPost,setSavedPost]=useState<{id:number;status:EditorStatus}|null>(null);
  const previews=useRef<Set<string>>(new Set());
  const key=draftKey(ownerKey,postId);
  const fileRef=useRef<HTMLInputElement>(null);
  function persist(next:EditorValues) {
    try {window.localStorage.setItem(key,JSON.stringify(snapshot(next)));setLocalError("");}
    catch {setLocalError("No se pudo guardar una copia local en este navegador. Guarda en el servidor antes de salir.");}
  }
  function checkpoint(next:EditorValues) {current.current=next;setValues(next);persist(next);}
  function change(update:(previous:EditorValues)=>EditorValues) {const next=update(current.current);current.current=next;setValues(next);setDirty(true);setSavedPost(null);setError("");persist(next);}
  async function readPreviews(id:number,base:EditorValues):Promise<EditorValues> {
    if (!base.media.length) return base;
    try {
      const urls=await request<ReadURLs>(`posts/${id}/media/read-urls`);
      const byId=new Map(urls.media.map(item=>[item.id,item]));
      return {...base,media:base.media.map(item=>({...item,preview:item.kind==="video"?(byId.get(item.id||"")?.poster_url||undefined):byId.get(item.id||"")?.url}))};
    } catch {setNotice("No se pudieron cargar las vistas previas. Puedes conservar y editar las fotos existentes.");return base;}
  }
  useEffect(()=>{
    let active=true;
    async function load() {
      try {
        const base=postId?valuesFromPost(await request<PrivatePost>(`posts/${postId}`)):blankValues();
        const next=postId?await readPreviews(postId,base):base;
        if (!active)return;
        current.current=next;setValues(next);setDirty(false);
        try {const stored=window.localStorage.getItem(key);if(stored){const draft=parseDraft(stored,postId);if(draft)setRecovery(draft);else setCorrupt(true);}}
        catch {setLocalError("Este navegador no permite guardar copias locales del escrito.");}
      } catch(error) {if(active)setLoadError(error instanceof EditorRequestError?error.message:"No se pudo cargar la publicación. Reintenta antes de editar.");}
      finally {if(active)setLoading(false);}
    }
    void load();return()=>{active=false;};
    // The route/account identify this editor. Preview loading is part of that load.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[postId,key,request,reload]);
  useEffect(()=>{let active=true;void request<Tag[]|{results:Tag[]}>("tags").then(result=>{if(active)setCatalog(Array.isArray(result)?result:result.results);}).catch(()=>{if(active)setTagsError("No se pudieron cargar las etiquetas. Las que ya elegiste se conservan.");}).finally(()=>{if(active)setTagsLoading(false);});return()=>{active=false;};},[request,tagsReload]);
  useEffect(()=>{
    if(!dirty)return;
    const unload=(event:BeforeUnloadEvent)=>{event.preventDefault();};
    const navigate=(event:MouseEvent)=>{
      const link=(event.target as Element)?.closest?.("a[href]") as HTMLAnchorElement|null;
      if(!link||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey||event.defaultPrevented||link.target==="_blank"||link.hasAttribute("download")||link.href===window.location.href||link.getAttribute("href")?.startsWith("#"))return;
      if(!window.confirm(localError?"Hay cambios sin guardar y no hay una copia local segura. ¿Salir del editor?":"Hay cambios sin guardar en el servidor. Conservaremos una copia local del texto; las fotos pendientes deberán elegirse otra vez. ¿Salir del editor?")){event.preventDefault();event.stopPropagation();}
    };
    window.addEventListener("beforeunload",unload);document.addEventListener("click",navigate,true);
    return()=>{window.removeEventListener("beforeunload",unload);document.removeEventListener("click",navigate,true);};
  },[dirty,localError]);
  useEffect(()=>{const urls=previews.current;return()=>{for(const preview of urls)URL.revokeObjectURL(preview);};},[]);
  async function restore() {
    if(!recovery)return;
    setLoading(true);setError("");
    try {
      // A saved draft can refer to a server-created post after a partial upload.
      const server=recovery.workingId?await request<PrivatePost>(`posts/${recovery.workingId}`):null;
      let next:EditorValues={...recovery,media:ensureCover(recovery.media.map(item=>{
        const remote=server?.media.find(media=>media.id===item.id);
        return {...item,ready:remote?.state==="ready",uploaded:item.uploaded||remote?.state==="ready"};
      }))};
      if(next.workingId)next=await readPreviews(next.workingId,next);
      checkpoint(next);setDirty(true);setMissingPhotos(recovery.missingPhotos);setUncertain(Boolean(recovery.uncertainCreation));setRecovery(null);
      setNotice("Copia local recuperada. Revisa el escrito antes de guardarlo: puede reemplazar cambios hechos desde otro dispositivo.");
    } catch(error) {setError(error instanceof EditorRequestError?error.message:"No se pudo recuperar la copia. Sigue disponible para reintentar.");}
    finally {setLoading(false);}
  }
  function discardRecovery() {
    try {window.localStorage.removeItem(key);setRecovery(null);setCorrupt(false);setLocalError("");}
    catch {setLocalError("No se pudo descartar la copia local. Reintenta.");}
  }
  function toggleTag(tag:Tag) {
    change(previous=>({...previous,tags:previous.tags.some(value=>value.slug===tag.slug)?previous.tags.filter(value=>value.slug!==tag.slug):[...previous.tags,tag],tagsDirty:true}));
  }
  async function choosePhotos(event:React.ChangeEvent<HTMLInputElement>) {
    const files=Array.from(event.target.files||[]);event.target.value="";if(!files.length)return;
    if(files.length>10-current.current.media.length){setError(`Puedes añadir ${10-current.current.media.length} fotos más. Elige menos archivos.`);return;}
    setInspecting(true);setError("");
    try {
      for(const file of files) {
        setProgress(`Preparando ${file.name}…`);
        const photo=await prepareEditorPhoto(file);const preview=URL.createObjectURL(photo.blob);previews.current.add(preview);
        const media:EditorMedia={key:crypto.randomUUID(),kind:"image",mime_type:photo.mime_type,alt_text:"",caption:"",is_cover:current.current.media.length===0,ready:false,photo,preview};
        change(previous=>({...previous,media:[...previous.media,media],mediaDirty:true}));
      }
      setNotice("Fotos preparadas. Los originales de tu laptop se conservan.");
    } catch(error) {setError(error instanceof Error?error.message:"No se pudo preparar la imagen. Prueba con otro archivo.");}
    finally {setInspecting(false);setProgress("");}
  }
  function updateMedia(key:string,patch:Partial<EditorMedia>) {change(previous=>({...previous,media:previous.media.map(item=>item.key===key?{...item,...patch}:item),mediaDirty:true}));}
  function removeMedia(item:EditorMedia) {
    if(item.id&&!window.confirm("¿Quitar esta foto o vídeo? Se eliminará de la publicación al guardar. Esta acción no se puede deshacer después de guardarla."))return;
    if(item.preview?.startsWith("blob:")){URL.revokeObjectURL(item.preview);previews.current.delete(item.preview);}
    change(previous=>({...previous,media:ensureCover(previous.media.filter(media=>media.key!==item.key)),deleted:item.id?[...new Set([...previous.deleted,item.id])]:previous.deleted,mediaDirty:true}));
  }
  function moveMedia(index:number,direction:number) {change(previous=>{const media=[...previous.media];[media[index],media[index+direction]]=[media[index+direction],media[index]];return{...previous,media,mediaDirty:true};});}
  async function save(status:EditorStatus) {
    if(saving||inspecting||loading||loadError||uncertain||missingPhotos||recovery||corrupt)return;
    if(!values.title.trim()||!values.content.trim()){setError("Escribe un título y contenido antes de guardar.");return;}
    if(status==="draft"&&current.current.originalStatus==="published"&&!window.confirm("Guardar como borrador retirará esta publicación del blog público. ¿Continuar?"))return;
    setSaving(true);setError("");setNotice("");
    try {
      const next=await saveEditorPost(current.current,status,{request,checkpoint,progress:setProgress});
      current.current=next;setValues(next);setDirty(false);setSavedPost({id:next.workingId!,status});
      try{window.localStorage.removeItem(key);setLocalError("");}catch{setLocalError("Guardado en el servidor. No se pudo borrar la copia local anterior; descártala si aparece al volver.");}
      setNotice(status==="published"?"Publicación guardada y publicada. Los cambios pueden tardar unos instantes en aparecer en el blog.":"Borrador guardado en el servidor.");
    } catch(error) {if(error instanceof UncertainCreationError)setUncertain(true);setError(error instanceof Error?error.message:"No se pudieron guardar todos los cambios. El escrito se conserva; reintenta.");}
    finally {setSaving(false);setProgress("");}
  }
  const disabled=saving||inspecting;const selectedSlugs=new Set(values.tags.map(tag=>tag.slug));const tagOptions=[...catalog,...values.tags.filter(tag=>!catalog.some(item=>item.slug===tag.slug))];
  if(loading)return <section className={styles.panel}><p role="status">Cargando el editor…</p></section>;
  if(loadError)return <section className={styles.panel}><h1>Editar publicación</h1><p role="alert" className={styles.error}>{loadError}</p><div className={styles.row}><button className={styles.secondary} onClick={()=>{setLoading(true);setLoadError("");setReload(value=>value+1);}}>Reintentar carga</button><Link href="/editor">Volver a mis publicaciones</Link></div></section>;
  return <section className={styles.panel} aria-labelledby="editor-title">
    <div className={styles.row}><h1 id="editor-title">{postId||values.workingId?"Editar publicación":"Nueva publicación"}</h1><span className={styles.badge}>{values.originalStatus==="published"?"Publicada":"Borrador"}</span></div>
    <p className={styles.muted}>El mismo espacio para escribir que llevas en Android, ahora en tu laptop.</p>
    <div className={styles.stack} style={{marginTop:"1.5rem"}}>
      {recovery&&<div className={styles.notice}><p>Hay una copia local de este escrito. Puedes recuperarla o continuar con la versión del servidor.</p><div className={styles.row}><button className={styles.secondary} onClick={restore}>Recuperar copia local</button><button className={styles.smallButton} onClick={discardRecovery}>Descartar copia local</button></div></div>}
      {corrupt&&<div className={styles.notice}><p role="alert">La copia local no se puede leer. Descartarla no cambia lo guardado en el servidor.</p><button className={styles.secondary} onClick={discardRecovery}>Descartar copia ilegible</button></div>}
      {notice&&<p className={styles.notice} role="status">{notice}</p>}
      {localError&&<p className={styles.error} role="alert">{localError}</p>}
      {missingPhotos&&<div className={styles.notice}><p>La copia local no incluye las fotos que todavía no habían subido. Vuelve a elegirlas y revisa la galería antes de guardar. Si una foto aparece como «Subida por completar» y no termina de guardarse, quítala y elígela otra vez.</p><button className={styles.secondary} onClick={()=>setMissingPhotos(false)}>Ya revisé las fotos recuperadas</button></div>}
      <label className={styles.field}>Título<input name="title" maxLength={200} required value={values.title} disabled={disabled} onChange={event=>change(previous=>({...previous,title:event.target.value}))}/></label>
      <label className={styles.field}>Contenido<textarea name="content" required rows={10} value={values.content} disabled={disabled} onChange={event=>change(previous=>({...previous,content:event.target.value}))}/></label>
      <fieldset className={styles.tags} disabled={disabled||tagsLoading||Boolean(tagsError)}><legend>Etiquetas · opcional ({values.tags.length}/5)</legend><p className={styles.muted}>Elige hasta cinco para encontrar esta publicación en el blog.</p><div className={styles.chips}>{tagOptions.map(tag=><button className={styles.chip} type="button" key={tag.slug} aria-pressed={selectedSlugs.has(tag.slug)} disabled={!selectedSlugs.has(tag.slug)&&values.tags.length>=5} onClick={()=>toggleTag(tag)}>{selectedSlugs.has(tag.slug)?"✓ ":""}#{tag.name}</button>)}</div></fieldset>
      {tagsLoading&&<p role="status">Cargando etiquetas…</p>}{tagsError&&<div><p role="alert" className={styles.error}>{tagsError}</p><button className={styles.secondary} disabled={disabled} onClick={()=>{setTagsLoading(true);setTagsError("");setTagsReload(value=>value+1);}}>Reintentar etiquetas</button></div>}
      <section className={styles.stack} aria-labelledby="photos-heading"><h2 id="photos-heading">Fotos ({values.media.length}/10)</h2><p className={styles.muted}>JPEG, PNG o WebP estáticos de hasta 32 MiB. Las optimizamos a un máximo de 2560 px. Los vídeos existentes se conservan; por ahora sólo puedes añadir fotos.</p><label className={styles.field}>Elegir fotos<input ref={fileRef} className={styles.fileInput} type="file" multiple accept="image/jpeg,image/png,image/webp" disabled={disabled||values.media.length>=10} onChange={choosePhotos}/></label>
      {values.media.map((item,index)=><article className={styles.mediaCard} key={item.key} aria-label={`Multimedia ${index+1}`}><div className={styles.mediaTop}>
        {/* Signed private previews and local blobs must never enter the public image optimizer. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        {item.preview?<img className={styles.mediaPreview} src={item.preview} alt={`Vista previa ${index+1}`} referrerPolicy="no-referrer"/>:<span className={`${styles.mediaPreview} ${styles.mediaPlaceholder}`}>{item.kind==="video"?"Vídeo":"Foto"}</span>}
        <div className={styles.mediaLabel}><strong>{item.is_cover?"Portada":`${item.kind==="video"?"Vídeo":"Foto"} ${index+1}`}</strong><p className={styles.muted}>{item.ready?"Guardada":item.id?"Subida por completar":"Nueva foto"}</p></div><button className={styles.smallButton} type="button" disabled={disabled} onClick={()=>removeMedia(item)}>Quitar</button></div>
        <div className={styles.choices}><button className={styles.smallButton} type="button" disabled={disabled||index===0} onClick={()=>moveMedia(index,-1)}>Subir</button><button className={styles.smallButton} type="button" disabled={disabled||index===values.media.length-1} onClick={()=>moveMedia(index,1)}>Bajar</button><button className={styles.smallButton} type="button" disabled={disabled||item.is_cover} onClick={()=>change(previous=>({...previous,media:previous.media.map(media=>({...media,is_cover:media.key===item.key})),mediaDirty:true}))}>Usar de portada</button></div>
        <label className={styles.field}>Texto alternativo<input value={item.alt_text} maxLength={500} disabled={disabled} onChange={event=>updateMedia(item.key,{alt_text:event.target.value})} placeholder="Describe lo importante de la imagen"/></label><label className={styles.field}>Pie de foto<input value={item.caption} maxLength={1000} disabled={disabled} onChange={event=>updateMedia(item.key,{caption:event.target.value})}/></label>
      </article>)}
      {values.media.length>0&&<fieldset className={styles.tags} disabled={disabled}><legend>Transición del carrusel</legend><div className={styles.choices}>{[["slide","Deslizar"],["fade","Desvanecer"],["bubble","Burbuja"],["none","Sin transición"]].map(([value,label])=><label key={value}><input type="radio" name="transition" checked={values.transition===value} onChange={()=>change(previous=>({...previous,transition:value as EditorValues["transition"],mediaDirty:true}))}/>{label}</label>)}</div></fieldset>}
      </section>
      {error&&<p role="alert" className={styles.error}>{error}</p>}
      {progress&&<p role="status">{progress}</p>}
      {uncertain&&<div className={styles.notice}><p>Por seguridad, no repetiremos la creación sin comprobar lo que se guardó. Tu texto sigue en esta copia local.</p><Link className={styles.secondary} href="/editor">Revisar mis publicaciones</Link></div>}
      <div className={styles.actions}><button className={styles.secondary} type="button" disabled={disabled||uncertain||missingPhotos||Boolean(recovery)||corrupt} onClick={()=>save("draft")}>Guardar borrador</button><button className={styles.primary} type="button" disabled={disabled||uncertain||missingPhotos||Boolean(recovery)||corrupt} onClick={()=>save("published")}>{saving?"Guardando…":values.originalStatus==="published"?"Guardar cambios publicados":"Publicar"}</button></div>
      {savedPost&&<div className={styles.row}><Link className={styles.textLink} href="/editor">Volver a mis publicaciones</Link><Link className={styles.textLink} href={`/editor/${savedPost.id}`}>Abrir versión guardada</Link></div>}
      <p className={styles.muted}>{dirty?(localError?"Cambios pendientes de guardar en el servidor.":"Copia del texto guardada en este navegador. Las fotos nuevas se suben al guardar."):"Los cambios se guardan en el servidor al pulsar uno de los botones."}</p>
    </div>
  </section>;
}
