function readBytes(file: Blob): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === 'function') return file.arrayBuffer();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(file);
  });
}

/**
 * SHA-256 (hex) over the file name, size and bytes, to spot the same file attached twice. Returns null when
 * the browser has no `crypto.subtle` (an insecure context); the caller then skips the duplicate check.
 */
export async function fileFingerprint(file: File): Promise<string | null> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  try {
    const head = new TextEncoder().encode(`${file.name}\0${file.size}\0`);
    const body = new Uint8Array(await readBytes(file));
    const data = new Uint8Array(head.length + body.length);
    data.set(head, 0);
    data.set(body, head.length);
    const digest = new Uint8Array(await subtle.digest('SHA-256', data));
    return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('');
  } catch {
    return null;
  }
}
