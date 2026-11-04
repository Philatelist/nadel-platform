import { useRef, useState } from 'react';
import { useT } from '../../i18n';
import { heicToJpeg, isHeicFile } from '../../lib/heicToJpeg';
import { UserContent } from '../UserContent';
import { Button, Notice } from '../ui';

export const MAX_FILES = 5;
export const MAX_FILE_BYTES = 5 * 1024 * 1024;
const ALLOWED = ['image/jpeg', 'image/png', 'application/pdf'];

/** A file of an earlier (reversed) expense that the server copies over. */
export interface ReusedFile {
  expenseId: string;
  n: number;
  fileName: string;
}

/**
 * Chooses up to five receipt files: a file picker and, for a phone, a camera button. The size, count and type
 * checks here are only hints; the server decides. A HEIC photo is converted to JPEG first.
 */
export function AttachmentPicker({
  files,
  reused,
  serverError,
  onAdd,
  onRemoveFile,
  onRemoveReused,
}: {
  files: readonly File[];
  reused: readonly ReusedFile[];
  serverError?: string;
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

  async function choose(list: FileList | null) {
    const chosen = list ? Array.from(list) : [];
    if (chosen.length === 0) return;
    setError(undefined);
    const accepted: File[] = [];
    let message: string | undefined;
    setConverting(true);
    try {
      for (const original of chosen) {
        let file: File | undefined = original;
        if (isHeicFile(original)) {
          file = await heicToJpeg(original);
          if (file === undefined) {
            message = a.heicFailed;
            continue;
          }
        }
        if (!ALLOWED.includes(file.type)) {
          message = a.typeNotAllowed;
        } else if (file.size > MAX_FILE_BYTES) {
          message = a.tooLarge;
        } else if (files.length + reused.length + accepted.length >= MAX_FILES) {
          message = a.tooMany;
        } else {
          accepted.push(file);
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
  return (
    <div className="flex flex-col gap-2">
      <span className="text-[15px] font-medium">{t.expenses.form.attachments}</span>
      <p className="text-sm text-ink-muted">{a.hint}</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={() => fileInput.current?.click()} disabled={converting}>
          {a.pick}
        </Button>
        <Button variant="secondary" onClick={() => cameraInput.current?.click()} disabled={converting}>
          {a.camera}
        </Button>
        <input
          ref={fileInput}
          type="file"
          multiple
          hidden
          data-testid="file-input"
          accept="image/jpeg,image/png,application/pdf,image/heic,image/heif,.heic,.heif"
          onChange={(e) => void choose(e.target.files)}
        />
        <input
          ref={cameraInput}
          type="file"
          hidden
          data-testid="camera-input"
          accept="image/*"
          capture="environment"
          onChange={(e) => void choose(e.target.files)}
        />
      </div>
      {converting && <p className="text-sm text-ink-muted">{a.converting}</p>}
      {shown && <Notice tone="danger">{shown}</Notice>}
      <ul className="flex flex-col gap-1">
        {reused.map((file, index) => (
          <li key={`r${file.n}`} className="flex flex-wrap items-center gap-3">
            <UserContent>{file.fileName}</UserContent>
            <span className="text-sm text-ink-muted">({a.reused})</span>
            <Button variant="secondary" onClick={() => onRemoveReused(index)}>
              {a.remove}
            </Button>
          </li>
        ))}
        {files.map((file, index) => (
          <li key={`${file.name}-${index}`} className="flex flex-wrap items-center gap-3">
            <UserContent>{file.name}</UserContent>
            <Button variant="secondary" onClick={() => onRemoveFile(index)}>
              {a.remove}
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
