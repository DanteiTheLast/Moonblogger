import { afterEach, describe, expect, it, vi } from "vitest";
import { editorRequest, blankValues, draftKey, ensureCover, parseDraft, snapshot, valuesFromPost } from "./model";

describe("private editor recovery", () => {
  afterEach(()=>vi.unstubAllGlobals());
  it("protects bodyless logout mutations with JSON and same-origin credentials", async()=>{
    const fetch=vi.fn().mockResolvedValue(new Response(null,{status:204}));vi.stubGlobal("fetch",fetch);
    await editorRequest("session",{method:"DELETE"});
    expect(fetch).toHaveBeenCalledWith("/api/editor/session",expect.objectContaining({credentials:"same-origin",cache:"no-store",headers:expect.objectContaining({"Content-Type":"application/json","X-MoonBlogger-Editor":"1"})}));
  });
  it("persists upload IDs but never files, previews or signed URLs", () => {
    const values = blankValues();
    values.workingId = 7;
    values.media = [{key:"first",id:"00000000-0000-0000-0000-000000000001",kind:"image",mime_type:"image/jpeg",ready:false,is_cover:true,alt_text:"Luna",caption:"",preview:"https://private/signed-token"}, {key:"local",kind:"image",mime_type:"image/jpeg",ready:false,is_cover:false,alt_text:"",caption:"",preview:"blob:private"}];
    const text = JSON.stringify(snapshot(values));
    expect(text).not.toContain("private");
    expect(parseDraft(text,null)).toMatchObject({workingId:7,missingPhotos:true,media:[{ready:false,id:"00000000-0000-0000-0000-000000000001"}]});
  });
  it("scopes copies to the account and original route; rejects foreign or corrupted recovery", () => {
    expect(draftKey("owner-A",1)).not.toBe(draftKey("owner-B",1));
    expect(draftKey("owner-A",null)).not.toBe(draftKey("owner-A",1));
    const values = blankValues(); values.workingId = 2;
    expect(parseDraft(JSON.stringify(snapshot(values)),1)).toBeNull();
    expect(parseDraft("broken",null)).toBeNull();
  });
  it("keeps ready gallery videos, tags and their order while excluding unattached intents", () => {
    const values = valuesFromPost({ id:1,title:"Moon",content:"hello",slug:"moon",status:"published",updated_at:"",tags:[{slug:"dan",name:"Dan"}],carousel_transition:"fade",media:[{id:"2",kind:"video",state:"ready",position:1,is_cover:false,mime_type:"video/mp4",alt_text:"v",caption:""},{id:"1",kind:"image",state:"ready",position:0,is_cover:true,mime_type:"image/jpeg",alt_text:"i",caption:""},{id:"3",kind:"image",state:"pending",position:null,is_cover:false,mime_type:"image/jpeg",alt_text:"",caption:""}] });
    expect(values.media.map(item=>item.id)).toEqual(["1","2"]);
    expect(values.tags[0].slug).toBe("dan");
    expect(ensureCover(values.media.slice(1))).toMatchObject([{is_cover:true,kind:"video"}]);
  });
  it("rejects invalid media and strips unknown persisted properties", () => {
    const draft = {...snapshot(blankValues()), token:"secret", preview:"signed-url"};
    expect(JSON.stringify(parseDraft(JSON.stringify(draft),null))).not.toContain("secret");
    expect(parseDraft(JSON.stringify({...draft,tags:Array(6).fill({slug:"dan",name:"Dan"})}),null)).toBeNull();
  });
});
