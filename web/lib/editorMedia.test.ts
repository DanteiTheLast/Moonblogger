import { afterEach, describe, expect, it, vi } from "vitest";
import { inspectPhotoHeader, outputPhotoDimensions, prepareEditorPhoto, uploadPhotoBytes, validatePhotoUploadUrl, type PreparedEditorPhoto } from "./editorMedia";

// Actual Pillow-encoded 32×16 images. JPEG includes EXIF orientation 6.
const JPEG = "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAYAAAAAAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQACADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDzWiiivSOEKKKKAP/Z";
const PNG = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAQCAYAAAB3AH1ZAAAAKElEQVR4nGNUsI5sYBhAwDSQlo86YNQBow4YdcCoA0YdMOoABgYGBgA5RQFUd8/mRAAAAABJRU5ErkJggg==";
const WEBP = "UklGRmIAAABXRUJQVlA4WAoAAAAQAAAAHwAADwAAQUxQSAoAAAABB1DAiAhERP8DVlA4IDIAAADQAgCdASogABAAPm0skkWkIqGYBABABsSygDsAAIGwAP71J1/+LYpDcz/w2qsloAAAAA==";
const bytes = (encoded: string) => new Uint8Array(Buffer.from(encoded, "base64"));
const uploadUrl = "https://zubconvayinjmnbqqxla.supabase.co/storage/v1/object/upload/sign/moonblogger-private/posts/abc/photo.jpg?token=signed-capability";

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("photo container inspection", () => {
  it.each([[JPEG, "image/jpeg", 6], [PNG, "image/png", 1], [WEBP, "image/webp", 1]] as const)("reads real %s image dimensions and orientation", (encoded, mime_type, orientation) => {
    expect(inspectPhotoHeader(bytes(encoded))).toEqual({ mime_type, width: 32, height: 16, orientation });
  });

  it.each(["GIF89a", "<svg xmlns='http://www.w3.org/2000/svg'/>", "not an image", "\0\0\0\x18ftypheic"])("rejects unsupported content regardless of filename", (content) => {
    expect(() => inspectPhotoHeader(new TextEncoder().encode(content))).toThrow(/formato/);
  });

  it("rejects animated WebP by its VP8X flag, before decoding", () => {
    const animated = bytes(WEBP); animated[20] |= 2;
    expect(() => inspectPhotoHeader(animated)).toThrow(/animadas/);
  });

  it("rejects APNG by its container chunk", () => {
    const still = bytes(PNG);
    const control = new Uint8Array([0, 0, 0, 8, 97, 99, 84, 76, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0]);
    const animated = new Uint8Array(still.length + control.length);
    animated.set(still.subarray(0, 33)); animated.set(control, 33); animated.set(still.subarray(33), 53);
    expect(() => inspectPhotoHeader(animated)).toThrow(/animadas/);
  });

  it.each([JPEG, PNG, WEBP])("rejects truncated containers", (encoded) => {
    expect(() => inspectPhotoHeader(bytes(encoded).subarray(0, 25))).toThrow();
  });

  it("rejects a decompression bomb before calling any codec", () => {
    const large = bytes(PNG);
    const view = new DataView(large.buffer);
    view.setUint32(16, 20_000); view.setUint32(20, 20_000);
    expect(() => inspectPhotoHeader(large)).toThrow(/100 megapíxeles/);
  });

  it.each([1, 2, 3, 4, 5, 6, 7, 8])("preserves output shape for EXIF orientation %i", (orientation) => {
    const result = outputPhotoDimensions({ mime_type: "image/jpeg", width: 6000, height: 4000, orientation });
    expect(result).toEqual(orientation < 5 ? { width: 2560, height: 1707 } : { width: 1707, height: 2560 });
  });

  it("does not upscale smaller images", () => {
    expect(outputPhotoDimensions(inspectPhotoHeader(bytes(JPEG)))).toEqual({ width: 16, height: 32 });
  });

  it("rejects an explicit MIME mismatch before calling the browser codec", async () => {
    const file = new File([Buffer.from(PNG, "base64")], "fake.jpg", { type: "image/jpeg" });
    await expect(prepareEditorPhoto(file)).rejects.toThrow(/formato/);
  });

  it("fails safely on browsers without preparation support instead of uploading the original", async () => {
    vi.stubGlobal("createImageBitmap", undefined);
    const file = new File([Buffer.from(PNG, "base64")], "photo.png", { type: "image/png" });
    await expect(prepareEditorPhoto(file)).rejects.toThrow(/navegador/);
  });
});

describe("signed photo upload", () => {
  it("allows only our private signed-upload endpoint", () => {
    expect(validatePhotoUploadUrl(uploadUrl).href).toBe(uploadUrl);
  });
  it.each([
    uploadUrl.replace("https:", "http:"),
    uploadUrl.replace("zubconvayinjmnbqqxla.supabase.co", "zubconvayinjmnbqqxla.supabase.co.evil.test"),
    uploadUrl.replace("moonblogger-private", "moonblogger-public"),
    uploadUrl.replace("/upload/sign/", "/sign/"),
    uploadUrl.replace("https://", "https://user:pass@"),
    uploadUrl + "#fragment",
    uploadUrl.split("?")[0],
    "not-a-url",
  ])("rejects untrusted destination %s", (url) => {
    expect(() => validatePhotoUploadUrl(url)).toThrow();
  });

  it("sends only prepared bytes with no cookies, auth or redirects", async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal("fetch", fetcher);
    const photo: PreparedEditorPhoto = { blob: new Blob([bytes(PNG)], { type: "image/png" }), mime_type: "image/png", width: 32, height: 16, size_bytes: bytes(PNG).length };
    await uploadPhotoBytes(uploadUrl, photo);
    expect(fetcher).toHaveBeenCalledWith(uploadUrl, expect.objectContaining({
      method: "PUT", body: photo.blob, headers: { "Content-Type": "image/png" },
      credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
    }));
  });

  it("does not hide failed transfers", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 403 }));
    const photo: PreparedEditorPhoto = { blob: new Blob(), mime_type: "image/jpeg", width: 1, height: 1, size_bytes: 1 };
    await expect(uploadPhotoBytes(uploadUrl, photo)).rejects.toThrow(/transferir/);
  });
});
