/** The longest side of a photo that is kept as it is; a larger one is scaled down to this. */
export const MAX_SIDE_PX = 2000;
/** A photo with more pixels than this is refused: decoding it could exhaust the memory of a phone. */
export const MAX_PIXELS = 50_000_000;
const JPEG_QUALITY = 0.85;

/** True for a HEIC/HEIF photo (iPhone), by its declared type or, when the browser gives none, its extension. */
export function isHeicFile(file: File): boolean {
  return /^image\/hei[cf]/i.test(file.type) || (file.type === '' && /\.hei[cf]$/i.test(file.name));
}

function jpegName(name: string): string {
  return `${name.replace(/\.[^./\\]+$/, '')}.jpg`;
}

/**
 * Prepares a photo for upload with the browser's own decoder (`createImageBitmap`, which applies the EXIF
 * orientation, a canvas and `toBlob`):
 * - HEIC/HEIF is always converted to JPEG (Safari and iOS decode it; elsewhere this returns `undefined`);
 * - a JPEG or PNG whose long side is at most {@link MAX_SIDE_PX} is returned unchanged;
 * - a larger JPEG or PNG is scaled to {@link MAX_SIDE_PX} on its long side and saved as JPEG (quality 0.85);
 * - more than {@link MAX_PIXELS} pixels is refused (`undefined`);
 * - a JPEG or PNG the browser cannot decode is returned unchanged: the server checks the file itself.
 * `undefined` means the caller shows its "type not allowed" message. The size limit is checked by the caller
 * after this step.
 */
export async function normalizeImage(file: File): Promise<File | undefined> {
  const heic = isHeicFile(file);
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    return heic ? undefined : file;
  }
  try {
    const { width, height } = bitmap;
    if (width * height > MAX_PIXELS) return undefined;
    const longSide = Math.max(width, height);
    if (!heic && longSide <= MAX_SIDE_PX) return file;
    const scale = longSide > MAX_SIDE_PX ? MAX_SIDE_PX / longSide : 1;
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(width * scale));
    canvas.height = Math.max(1, Math.round(height * scale));
    canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY),
    );
    return blob === null ? undefined : new File([blob], jpegName(file.name), { type: 'image/jpeg' });
  } finally {
    bitmap.close();
  }
}
