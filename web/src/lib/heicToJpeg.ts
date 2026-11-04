/** True for a HEIC/HEIF photo (iPhone), by its declared type or, when the browser gives none, its extension. */
export function isHeicFile(file: File): boolean {
  return /^image\/hei[cf]/i.test(file.type) || (file.type === '' && /\.hei[cf]$/i.test(file.name));
}

function jpegName(name: string): string {
  return `${name.replace(/\.[^./\\]+$/, '')}.jpg`;
}

/**
 * Converts a HEIC/HEIF photo to JPEG with the browser's own decoder: `createImageBitmap`, a canvas and
 * `toBlob`. Safari and iOS decode HEIC natively; where the browser cannot, this returns `undefined` and the
 * caller shows the "type not allowed" error.
 */
export async function heicToJpeg(file: File): Promise<File | undefined> {
  try {
    const bitmap = await createImageBitmap(file);
    try {
      const canvas = document.createElement('canvas');
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      canvas.getContext('2d')?.drawImage(bitmap, 0, 0);
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
      return blob === null ? undefined : new File([blob], jpegName(file.name), { type: 'image/jpeg' });
    } finally {
      bitmap.close();
    }
  } catch {
    return undefined;
  }
}
