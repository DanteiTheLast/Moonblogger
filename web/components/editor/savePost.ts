import {uploadPhotoBytes} from "@/lib/editorMedia";
import {EditorRequestError, type EditorStatus, type EditorValues, type PrivatePost} from "./model";
type Request = <T>(path:string,options?:RequestInit)=>Promise<T>;
interface Options {request:Request;checkpoint:(values:EditorValues)=>void;progress:(message:string)=>void;}
export class UncertainCreationError extends Error {}
export async function saveEditorPost(values:EditorValues,status:EditorStatus,{request,checkpoint,progress}:Options):Promise<EditorValues> {
  if (values.uncertainCreation) throw new UncertainCreationError("Revisa Mis publicaciones antes de continuar; falta confirmar una creación anterior.");
  const current:EditorValues={...values,media:values.media.map(item=>({...item})),deleted:[...values.deleted]};
  const persist=()=>checkpoint({...current,media:current.media.map(item=>({...item})),deleted:[...current.deleted]});
  const fields={title:current.title.trim(),content:current.content.trim(),carousel_transition:current.transition,...(current.tagsDirty||!current.workingId?{tag_slugs:current.tags.map(tag=>tag.slug)}:{})};
  if (!current.workingId) {
    progress("Guardando el escrito como borrador…");
    try { const post=await request<PrivatePost>("posts",{method:"POST",body:JSON.stringify({...fields,status:"draft"})}); current.workingId=post.id; persist(); }
    catch(error) {
      if (!(error instanceof EditorRequestError) || error.status===0 || error.status>=500) {current.uncertainCreation=true;persist();throw new UncertainCreationError("Se perdió la confirmación del borrador. Revisa Mis publicaciones antes de crear otro; así evitamos duplicarlo.");}
      throw error;
    }
  } else {
    progress("Guardando el contenido…");
    // Omitting status keeps a published post visible while media are prepared.
    await request(`posts/${current.workingId}`,{method:"PATCH",body:JSON.stringify(fields)});
  }
  const postPath=`posts/${current.workingId}`;
  for (const id of [...current.deleted]) {
    progress("Quitando las fotos confirmadas…");
    try {await request(`${postPath}/media/${id}`,{method:"DELETE"});} catch(error) {if (!(error instanceof EditorRequestError)||error.status!==404) throw error;}
    current.deleted=current.deleted.filter(item=>item!==id);persist();
  }
  for (let index=0;index<current.media.length;index++) {
    const media=current.media[index];
    if (!media.ready) {
      progress(`Subiendo foto ${index+1} de ${current.media.length}…`);
      if (!media.id) {
        if (!media.photo) throw new Error("Vuelve a elegir las fotos que todavía no se habían subido.");
        let intent:{media_id:string;upload_url:string};
        try {intent=await request(`${postPath}/media/upload-intents`,{method:"POST",body:JSON.stringify({kind:"image",mime_type:media.photo.mime_type,size_bytes:media.photo.size_bytes,width:media.photo.width,height:media.photo.height})});}
        catch(error) {if (!(error instanceof EditorRequestError)||error.status===0||error.status>=500) {current.uncertainCreation=true;persist();throw new UncertainCreationError("Se perdió la confirmación de una foto. Revisa tus publicaciones antes de volver a elegirla.");}throw error;}
        media.id=intent.media_id;media.uploadUrl=intent.upload_url;persist();
      }
      if (!media.uploaded && !media.uploadAttempted && media.photo && media.uploadUrl) {
        // A PUT can have succeeded even when its response is lost. Never repeat
        // that non-upsert signed upload: complete/reconcile it below instead.
        media.uploadAttempted=true;persist();
        try {await uploadPhotoBytes(media.uploadUrl,media.photo);media.uploaded=true;persist();}
        catch { /* Completion verifies whether storage accepted the bytes. */ }
      }
      // Completion is not idempotent in Django. Reconcile a lost acknowledgement
      // against the private post, including ready items not attached to layout yet.
      try { await request(`${postPath}/media/complete`,{method:"POST",body:JSON.stringify({media_id:media.id})}); }
      catch (error) {
        if (error instanceof EditorRequestError && error.status === 401) throw error;
        const server = await request<PrivatePost>(postPath);
        const known = server.media.find(item => item.id === media.id);
        if (known?.state !== "ready") {
          if (!media.uploaded) throw new Error("Esta foto no terminó de subir. Quítala y vuelve a elegirla para continuar.");
          throw error;
        }
      }
      media.ready=true;persist();
    }
    await request(`${postPath}/media/${media.id}`,{method:"PATCH",body:JSON.stringify({alt_text:media.alt_text,caption:media.caption})});
  }
  if (current.mediaDirty || current.media.length || current.deleted.length) {
    progress("Guardando la portada y el orden…");
    await request(`${postPath}/media/layout`,{method:"PUT",body:JSON.stringify({items:current.media.map((item,position)=>({id:item.id,position,is_cover:item.is_cover})),carousel_transition:current.transition})});
  }
  progress(status==="published"?"Publicando…":"Guardando el borrador…");
  await request(`${postPath}`,{method:"PATCH",body:JSON.stringify({status})});
  current.status=status;current.originalStatus=status;current.tagsDirty=false;current.mediaDirty=false;persist();
  return current;
}
