/** Browser-only photo preparation; originals never leave the device. */
export const MAX_PHOTO_INPUT_BYTES = 32 * 1024 * 1024;
export const MAX_PHOTO_BYTES = 8 * 1024 * 1024;
export const MAX_PHOTO_SIDE = 2560;
const MAX_SOURCE_PIXELS = 100_000_000;

export type PhotoSourceInfo = {
  mime_type: "image/jpeg" | "image/png" | "image/webp";
  width: number;
  height: number;
  orientation: number;
};
export type PreparedEditorPhoto = {
  blob: Blob;
  mime_type: "image/jpeg" | "image/png";
  width: number;
  height: number;
  size_bytes: number;
};

function invalidPhoto(): never {
  throw new Error("La imagen está dañada o tiene un formato no compatible. Usa JPEG, PNG o WebP estático.");
}
function animatedPhoto(): never {
  throw new Error("Las imágenes animadas no se admiten en las publicaciones. Elige una imagen estática.");
}
function text(bytes: Uint8Array, start: number, length: number) {
  return String.fromCharCode(...bytes.subarray(start, start + length));
}

/** Read only orientation from TIFF; malformed optional metadata cannot escape its segment. */
function exifOrientation(bytes: Uint8Array): number {
  let start = 0;
  if (text(bytes, 0, 6) === "Exif\0\0") start = 6;
  if (bytes.length < start + 8) return 1;
  const order = text(bytes, start, 2);
  if (order !== "II" && order !== "MM") return 1;
  const little = order === "II";
  const view = new DataView(bytes.buffer, bytes.byteOffset + start, bytes.length - start);
  if (view.getUint16(2, little) !== 42) return 1;
  const offset = view.getUint32(4, little);
  if (offset > view.byteLength - 2) return 1;
  const entries = view.getUint16(offset, little);
  for (let i = 0; i < entries; i++) {
    const entry = offset + 2 + i * 12;
    if (entry > view.byteLength - 12) return 1;
    if (view.getUint16(entry, little) === 0x0112 &&
        view.getUint16(entry + 2, little) === 3 && view.getUint32(entry + 4, little) === 1) {
      const orientation = view.getUint16(entry + 8, little);
      return orientation >= 1 && orientation <= 8 ? orientation : 1;
    }
  }
  return 1;
}

/** Inspect actual container bytes, including animation flags, before asking a codec to decode. */
export function inspectPhotoHeader(bytes: Uint8Array): PhotoSourceInfo {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let mime_type: PhotoSourceInfo["mime_type"];
  let width = 0;
  let height = 0;
  let orientation = 1;
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    mime_type = "image/jpeg";
    let position = 2;
    while (position < bytes.length) {
      if (bytes[position++] !== 0xff) invalidPhoto();
      while (bytes[position] === 0xff) position++;
      const marker = bytes[position++];
      if (marker === 0xda || marker === 0xd9) break;
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
      if (position + 2 > bytes.length) invalidPhoto();
      const length = view.getUint16(position);
      if (length < 2 || position + length > bytes.length) invalidPhoto();
      if (marker === 0xe1 && text(bytes, position + 2, 6) === "Exif\0\0") {
        orientation = exifOrientation(bytes.subarray(position + 2, position + length));
      }
      if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
        if (length < 8) invalidPhoto();
        height = view.getUint16(position + 3);
        width = view.getUint16(position + 5);
      }
      position += length;
    }
  } else if (bytes.length >= 8 && bytes[0] === 0x89 && text(bytes, 1, 7) === "PNG\r\n\x1a\n") {
    mime_type = "image/png";
    let position = 8;
    let ended = false;
    while (position + 12 <= bytes.length) {
      const length = view.getUint32(position);
      const kind = text(bytes, position + 4, 4);
      if (length > bytes.length - position - 12) invalidPhoto();
      if (position === 8 && (kind !== "IHDR" || length !== 13)) invalidPhoto();
      if (kind === "IHDR") {
        if (position !== 8 || length !== 13) invalidPhoto();
        width = view.getUint32(position + 8);
        height = view.getUint32(position + 12);
      }
      if (kind === "acTL") animatedPhoto();
      if (kind === "eXIf") orientation = exifOrientation(bytes.subarray(position + 8, position + 8 + length));
      position += length + 12;
      if (kind === "IEND") { ended = true; break; }
    }
    if (!ended) invalidPhoto();
  } else if (bytes.length >= 12 && text(bytes, 0, 4) === "RIFF" && text(bytes, 8, 4) === "WEBP") {
    mime_type = "image/webp";
    const end = view.getUint32(4, true) + 8;
    if (end !== bytes.length) invalidPhoto();
    let position = 12;
    while (position + 8 <= end) {
      const kind = text(bytes, position, 4);
      const length = view.getUint32(position + 4, true);
      const data = position + 8;
      if (length > end - data) invalidPhoto();
      if (kind === "ANIM" || kind === "ANMF") animatedPhoto();
      if (kind === "VP8X") {
        if (length !== 10) invalidPhoto();
        if (bytes[data] & 0x02) animatedPhoto();
        width = 1 + bytes[data + 4] + (bytes[data + 5] << 8) + (bytes[data + 6] << 16);
        height = 1 + bytes[data + 7] + (bytes[data + 8] << 8) + (bytes[data + 9] << 16);
      } else if (kind === "VP8 " && !width) {
        if (length < 10 || text(bytes, data + 3, 3) !== "\x9d\x01\x2a") invalidPhoto();
        width = view.getUint16(data + 6, true) & 0x3fff;
        height = view.getUint16(data + 8, true) & 0x3fff;
      } else if (kind === "VP8L" && !width) {
        if (length < 5 || bytes[data] !== 0x2f) invalidPhoto();
        const bits = view.getUint32(data + 1, true);
        width = (bits & 0x3fff) + 1;
        height = ((bits >>> 14) & 0x3fff) + 1;
      } else if (kind === "EXIF") orientation = exifOrientation(bytes.subarray(data, data + length));
      position = data + length + length % 2;
    }
    if (position !== end) invalidPhoto();
  } else invalidPhoto();
  if (width < 1 || height < 1 || width * height > MAX_SOURCE_PIXELS) {
    throw new Error("La imagen es demasiado grande o no se puede leer (máximo 100 megapíxeles).");
  }
  return { mime_type, width, height, orientation };
}

export function outputPhotoDimensions(source: PhotoSourceInfo) {
  const swapsSides = source.orientation >= 5;
  const width = swapsSides ? source.height : source.width;
  const height = swapsSides ? source.width : source.height;
  const scale = Math.min(1, MAX_PHOTO_SIDE / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

export async function prepareEditorPhoto(file: File): Promise<PreparedEditorPhoto> {
  if (file.size === 0 || file.size > MAX_PHOTO_INPUT_BYTES) {
    throw new Error("Elige una imagen de hasta 32 MiB.");
  }
  const source = inspectPhotoHeader(new Uint8Array(await file.arrayBuffer()));
  // Empty/octet-stream MIME is common in file pickers; explicit conflicting MIME is rejected.
  const declared = file.type.toLowerCase().replace("image/jpg", "image/jpeg").replace("image/x-png", "image/png");
  if (declared && declared !== "application/octet-stream" && declared !== source.mime_type) invalidPhoto();
  if (typeof createImageBitmap !== "function") {
    throw new Error("Este navegador no permite preparar fotos. Usa una versión reciente de Chrome, Firefox, Edge o Safari.");
  }
  const dimensions = outputPhotoDimensions(source);
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, {
      imageOrientation: "from-image", resizeWidth: dimensions.width,
      resizeHeight: dimensions.height, resizeQuality: "high", colorSpaceConversion: "default",
    });
  } catch { invalidPhoto(); }
  const canvas = document.createElement("canvas");
  try {
    if (bitmap.width !== dimensions.width || bitmap.height !== dimensions.height) {
      throw new Error("El navegador no pudo reducir esta foto correctamente. Prueba con otro navegador.");
    }
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d", { willReadFrequently: true, colorSpace: "srgb" });
    if (!context) throw new Error("No se pudo preparar la imagen.");
    context.drawImage(bitmap, 0, 0);
    let transparent = false;
    // Small stripes avoid a second full 26 MiB buffer and thousands of canvas reads.
    for (let y = 0; y < canvas.height && !transparent; y += 64) {
      const row = context.getImageData(0, y, canvas.width, Math.min(64, canvas.height - y)).data;
      for (let i = 3; i < row.length; i += 4) {
        if (row[i] !== 255) { transparent = true; break; }
      }
    }
    const mime_type = transparent ? "image/png" : "image/jpeg";
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((result) => result ? resolve(result) : reject(new Error("No se pudo comprimir la foto.")), mime_type, 0.88);
    });
    if (!blob.size || blob.size > MAX_PHOTO_BYTES || blob.type !== mime_type) {
      throw new Error("La imagen preparada supera 8 MiB. Reduce sus dimensiones y vuelve a intentarlo.");
    }
    return { blob, mime_type, ...dimensions, size_bytes: blob.size };
  } finally {
    bitmap.close();
    canvas.width = canvas.height = 0;
  }
}

/** Signed upload capability must remain on our private storage origin, never the BFF session. */
export function validatePhotoUploadUrl(value: string): URL {
  let url: URL;
  try { url = new URL(value); } catch { throw new Error("La dirección de subida no es válida."); }
  if (url.protocol !== "https:" || url.hostname !== "zubconvayinjmnbqqxla.supabase.co" ||
      url.port || url.username || url.password || url.hash ||
      !url.pathname.startsWith("/storage/v1/object/upload/sign/moonblogger-private/") ||
      !url.searchParams.get("token")) {
    throw new Error("La dirección de subida no pertenece al almacenamiento de MoonBlogger.");
  }
  return url;
}

/** Intent creation and completion deliberately belong to the editor, which retains the media ID. */
export async function uploadPhotoBytes(uploadUrl: string, photo: PreparedEditorPhoto, signal?: AbortSignal): Promise<void> {
  const url = validatePhotoUploadUrl(uploadUrl);
  const response = await fetch(url.href, {
    method: "PUT", body: photo.blob, headers: { "Content-Type": photo.mime_type },
    credentials: "omit", redirect: "error", referrerPolicy: "no-referrer", signal,
  });
  if (!response.ok) throw new Error("No se pudo transferir la foto. Vuelve a intentar la subida.");
}
