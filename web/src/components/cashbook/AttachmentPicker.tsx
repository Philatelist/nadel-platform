import { useRef, useState } from 'react';
import { useT } from '../../i18n';
import { heicToJpeg, isHeicFile } from '../../lib/heicToJpeg';
import { Button, Notice } from '../ui';
import { AttachmentPreviews, type PreviewFile } from './AttachmentPreview';

export const MAX_FILES = 5;
export const MAX_FILE_BYTES = 5 * 1024 * 1024;
const ALLOWED = ['image/jpeg', 'image/png', 'application/pdf'];

/** A file of an earlier (reversed) expense that the server copies over. */
export interface ReusedFile {
  expenseId: string;
  n: number;
  fileName: string;
  contentType?: string;
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
  const previews: PreviewFile[] = [
    ...reused.map((file) => ({
      key: `r${file.expenseId}-${file.n}`,
      fileName: file.fileName,
      contentType: file.contentType ?? '',
      stored: { expenseId: file.expenseId, n: file.n },
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
      <AttachmentPreviews
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
