import {beforeEach,describe,expect,it,vi} from "vitest";
vi.mock("@/lib/editorMedia",()=>({uploadPhotoBytes:vi.fn().mockResolvedValue(undefined)}));
import {blankValues,EditorRequestError,type EditorValues} from "./model";
import {uploadPhotoBytes} from "@/lib/editorMedia";
import {saveEditorPost,UncertainCreationError} from "./savePost";

describe("editor save checkpoints",()=>{
  beforeEach(()=>{vi.mocked(uploadPhotoBytes).mockReset().mockResolvedValue(undefined);});
  it("creates privately and checkpoints intent and completed bytes before publishing",async()=>{
    const initial=blankValues();initial.title="Moon";initial.content="Texto";
    initial.media=[{key:"photo",kind:"image",mime_type:"image/jpeg",alt_text:"Cat",caption:"",is_cover:true,ready:false,photo:{blob:new Blob(["photo"]),size_bytes:5,mime_type:"image/jpeg",width:10,height:10}}];initial.mediaDirty=true;
    const calls:{path:string;body:Record<string,unknown>}[]=[];const checkpoints:EditorValues[]=[];
    const request=vi.fn(async(path:string,options?:RequestInit)=>{calls.push({path,body:options?.body?JSON.parse(options.body as string):{}});if(path==="posts")return{id:7};if(path.endsWith("upload-intents"))return{media_id:"abc",upload_url:"https://upload"};return{};});
    await saveEditorPost(initial,"published",{request:request as never,checkpoint:value=>checkpoints.push(value),progress:()=>{}});
    expect(calls[0].body.status).toBe("draft");expect(calls.at(-1)?.body.status).toBe("published");
    expect(checkpoints.some(value=>value.media[0].id==="abc"&&!value.media[0].ready)).toBe(true);
    expect(checkpoints.some(value=>value.media[0].uploaded&&!value.media[0].ready)).toBe(true);
    expect(calls.findIndex(call=>call.path.endsWith("complete"))).toBeLessThan(calls.findIndex(call=>call.path.endsWith("layout")));
  });
  it("keeps the known media ID after complete fails and retries without a new intent",async()=>{
    const initial=blankValues();initial.workingId=7;initial.title="Moon";initial.content="Text";initial.media=[{key:"known",id:"known",kind:"image",mime_type:"image/jpeg",alt_text:"",caption:"",is_cover:true,ready:false,uploaded:true}];
    let checkpoint=initial;
    const request=vi.fn(async(path:string)=>{if(path.endsWith("complete"))throw new EditorRequestError("Offline");return{media:[{id:"known",state:"pending"}]};});
    await expect(saveEditorPost(initial,"published",{request:request as never,checkpoint:value=>{checkpoint=value;},progress:()=>{}})).rejects.toThrow("Offline");
    expect(checkpoint.media[0].id).toBe("known");expect(request.mock.calls.some(([path])=>path.endsWith("upload-intents"))).toBe(false);
  });
  it("reconciles a lost completion acknowledgement without repeating an upload intent",async()=>{
    const initial=blankValues();initial.workingId=7;initial.media=[{key:"known",id:"known",kind:"image",mime_type:"image/jpeg",alt_text:"",caption:"",is_cover:true,ready:false,uploaded:true}];
    const request=vi.fn(async(path:string,options?:RequestInit)=>{if(path.endsWith("complete"))throw new EditorRequestError("Already complete",400);if(path==="posts/7"&&!options)return{media:[{id:"known",state:"ready",position:null}]};return{};});
    const result=await saveEditorPost(initial,"published",{request:request as never,checkpoint:()=>{},progress:()=>{}});
    expect(result.media[0].ready).toBe(true);expect(request.mock.calls.some(([path])=>path.endsWith("upload-intents"))).toBe(false);
  });
  it("blocks an uncertain intent in the persisted checkpoint",async()=>{
    const initial=blankValues();initial.workingId=7;initial.media=[{key:"new",kind:"image",mime_type:"image/jpeg",alt_text:"",caption:"",is_cover:true,ready:false,photo:{blob:new Blob(["photo"]),size_bytes:5,mime_type:"image/jpeg",width:10,height:10}}];
    let checkpoint=initial;
    const request=vi.fn(async(path:string)=>{if(path.endsWith("upload-intents"))throw new EditorRequestError("Offline");return{};});
    await expect(saveEditorPost(initial,"published",{request:request as never,checkpoint:value=>{checkpoint=value;},progress:()=>{}})).rejects.toBeInstanceOf(UncertainCreationError);
    expect(checkpoint.uncertainCreation).toBe(true);
    await expect(saveEditorPost(checkpoint,"published",{request:request as never,checkpoint:()=>{},progress:()=>{}})).rejects.toBeInstanceOf(UncertainCreationError);
    expect(request.mock.calls.filter(([path])=>path.endsWith("upload-intents"))).toHaveLength(1);
  });
  it("checkpoints attempted bytes before PUT and reconciles a lost PUT acknowledgement",async()=>{
    const initial=blankValues();initial.workingId=7;initial.mediaDirty=true;
    initial.media=[{key:"new",kind:"image",mime_type:"image/jpeg",alt_text:"",caption:"",is_cover:true,ready:false,photo:{blob:new Blob(["photo"]),size_bytes:5,mime_type:"image/jpeg",width:10,height:10}}];
    let checkpoint=initial;
    vi.mocked(uploadPhotoBytes).mockImplementation(async()=>{expect(checkpoint.media[0].uploadAttempted).toBe(true);throw new Error("Lost acknowledgement");});
    const request=vi.fn(async(path:string)=>{if(path.endsWith("upload-intents"))return{media_id:"known",upload_url:"https://upload"};return{};});
    const result=await saveEditorPost(initial,"published",{request:request as never,checkpoint:value=>{checkpoint=value;},progress:()=>{}});
    expect(result.media[0].ready).toBe(true);expect(uploadPhotoBytes).toHaveBeenCalledTimes(1);
    expect(request.mock.calls.some(([path])=>path.endsWith("complete"))).toBe(true);
  });
  it("does not retry a non-upsert PUT after uncertain byte delivery",async()=>{
    const initial=blankValues();initial.workingId=7;initial.mediaDirty=true;
    initial.media=[{key:"new",kind:"image",mime_type:"image/jpeg",alt_text:"",caption:"",is_cover:true,ready:false,photo:{blob:new Blob(["photo"]),size_bytes:5,mime_type:"image/jpeg",width:10,height:10}}];
    let checkpoint=initial;let attempts=0;
    vi.mocked(uploadPhotoBytes).mockRejectedValue(new Error("Connection lost"));
    const request=vi.fn(async(path:string,options?:RequestInit)=>{
      if(path.endsWith("upload-intents"))return{media_id:"known",upload_url:"https://upload"};
      if(path.endsWith("complete")){attempts++;throw new EditorRequestError("Cannot confirm",400);}
      if(path==="posts/7"&&!options)return{media:[{id:"known",state:attempts===1?"pending":"ready"}]};return{};
    });
    await expect(saveEditorPost(initial,"published",{request:request as never,checkpoint:value=>{checkpoint=value;},progress:()=>{}})).rejects.toThrow("no terminó de subir");
    expect(checkpoint.media[0].uploadAttempted).toBe(true);
    const result=await saveEditorPost(checkpoint,"published",{request:request as never,checkpoint:value=>{checkpoint=value;},progress:()=>{}});
    expect(result.media[0].ready).toBe(true);expect(uploadPhotoBytes).toHaveBeenCalledTimes(1);
    expect(request.mock.calls.filter(([path])=>path.endsWith("upload-intents"))).toHaveLength(1);
  });
  it("does not unpublish an existing post during preparation and blocks uncertain creates",async()=>{
    const initial=blankValues();initial.workingId=2;initial.originalStatus="published";
    const request=vi.fn().mockResolvedValue({});
    await saveEditorPost(initial,"published",{request,checkpoint:()=>{},progress:()=>{}});
    expect(JSON.parse(request.mock.calls[0][1].body)).not.toHaveProperty("status");
    let recovered=blankValues();const failed=vi.fn().mockRejectedValue(new EditorRequestError("Offline"));
    await expect(saveEditorPost(blankValues(),"draft",{request:failed,checkpoint:value=>{recovered=value;},progress:()=>{}})).rejects.toBeInstanceOf(UncertainCreationError);
    expect(recovered.uncertainCreation).toBe(true);expect(failed).toHaveBeenCalledTimes(1);
  });
});
