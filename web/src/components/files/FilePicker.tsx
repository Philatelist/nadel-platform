import { useRef, useState } from 'react';
import { useT } from '../../i18n';
import { fileFingerprint } from '../../lib/fileFingerprint';
import { isHeicFile, normalizeImage } from '../../lib/normalizeImage';
import { Button, Notice } from '../ui';
import { FilePreviews, type PreviewFile } from './FilePreview';

export const MAX_FILES = 5;
export const MAX_FILE_BYTES = 5 * 1024 * 1024;
const ALLOWED = ['image/jpeg', 'image/png', 'application/pdf'];
const DEFAULT_ACCEPT = 'image/jpeg,image/png,application/pdf,image/heic,image/heif,.heic,.heif';

/** A file already stored on the server that the form carries over; `load` fetches its bytes for the preview. */
export interface StoredFile {
  key: string;
  fileName: string;
  contentType?: string;
  load: () => Promise<Blob>;
}

/**
 * Chooses up to `maxFiles` files (receipts): a file picker and, for a phone, a camera button. The size, count and type
 * checks here are only hints; the server decides. A HEIC photo is converted to JPEG first.
 */
export function FilePicker({
  files,
  reused,
  serverError,
  maxFiles = MAX_FILES,
  accept = DEFAULT_ACCEPT,
  capture,
  onAdd,
  onRemoveFile,
  onRemoveReused,
}: {
  files: readonly File[];
  reused: readonly StoredFile[];
  serverError?: string;
  /** At most this many files in all, stored ones included. */
  maxFiles?: number;
  /** The `accept` of the file chooser. */
  accept?: string;
  /** When set, a camera button opens the camera of that side (phones). */
  capture?: 'environment' | 'user';
  onAdd: (added: File[]) => void;
  onRemoveFile: (index: number) => void;
  onRemoveReused: (index: number) => void;
}) {
  const t = useT();
  const a = t.expenses.attachments;
  const fileInput = useRef<HTMLInputElement>(null);
  const cameraInput = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string>();
  const [converting, setConverting] = useState(false);
  // Fingerprint of the file the user picked, by the attached (possibly converted) file; removal drops it.
  const fingerprints = useRef(new WeakMap<File, string>());

  async function choose(list: FileList | null) {
    const chosen = list ? Array.from(list) : [];
    if (chosen.length === 0) return;
    setError(undefined);
    const accepted: File[] = [];
    const seen = new Set<string>();
    for (const file of files) {
      const known = fingerprints.current.get(file);
      if (known) seen.add(known);
    }
    let message: string | undefined;
    setConverting(true);
    try {
      for (const original of chosen) {
        const print = await fileFingerprint(original);
        if (print !== null && seen.has(print)) {
          message = a.duplicate;
          continue;
        }
        const heic = isHeicFile(original);
        // HEIC is always converted; a large photo is scaled down; a PDF or a small photo stays as it is
        const file = original.type === 'application/pdf' ? original : await normalizeImage(original);
        if (file === undefined) {
          message = heic ? a.heicFailed : a.typeNotAllowed;
          continue;
        }
        if (!ALLOWED.includes(file.type)) {
          message = a.typeNotAllowed;
        } else if (file.size > MAX_FILE_BYTES) {
          message = a.tooLarge;
        } else if (files.length + reused.length + accepted.length >= maxFiles) {
          message = a.tooMany;
        } else {
          accepted.push(file);
          if (print !== null) {
            seen.add(print);
            fingerprints.current.set(file, print);
          }
        }
      }
    } finally {
      setConverting(false);
    }
    setError(message);
    if (accepted.length > 0) onAdd(accepted);
    if (fileInput.current) fileInput.current.value = '';
    if (cameraInput.current) cameraInput.current.value = '';
  }

  const shown = error ?? serverError;
  const previews: PreviewFile[] = [
    ...reused.map((file) => ({
      key: file.key,
      fileName: file.fileName,
      contentType: file.contentType ?? '',
      load: file.load,
    })),
    ...files.map((file, index) => ({
      key: `f${index}-${file.name}-${file.size}`,
      fileName: file.name,
      contentType: file.type,
      local: file,
    })),
  ];
  return (
    <div className="flex flex-col gap-2">
      <span className="text-[15px] font-medium">{t.expenses.form.attachments}</span>
      <p className="text-sm text-ink-muted">{a.hint}</p>
      <p className="text-sm text-ink-muted">{a.heicHint}</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={() => fileInput.current?.click()} disabled={converting}>
          {a.pick}
        </Button>
        {capture !== undefined && (
          <Button variant="secondary" onClick={() => cameraInput.current?.click()} disabled={converting}>
            {a.camera}
          </Button>
        )}
        <input
          ref={fileInput}
          type="file"
          multiple
          hidden
          data-testid="file-input"
          accept={accept}
          onChange={(e) => void choose(e.target.files)}
        />
        {capture !== undefined && (
          <input
            ref={cameraInput}
            type="file"
            hidden
            data-testid="camera-input"
            accept="image/*"
            capture={capture}
            onChange={(e) => void choose(e.target.files)}
          />
        )}
      </div>
      {converting && <p className="text-sm text-ink-muted">{a.converting}</p>}
      {shown && <Notice tone="danger">{shown}</Notice>}
      <FilePreviews
        files={previews}
        actions={(file, index) => (
          <Button
            variant="secondary"
            aria-label={`${a.remove}: ${file.fileName}`}
            onClick={() =>
              index < reused.length ? onRemoveReused(index) : onRemoveFile(index - reused.length)
            }
          >
            {a.remove}
          </Button>
        )}
      />
    </div>
  );
}
