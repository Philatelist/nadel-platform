import { downloadExpenseAttachment, type ExpenseAttachment } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useT } from '../../i18n';
import { usePdfAction } from '../../hooks/usePdfAction';
import { problemMessage } from '../../problem';
import { Button, Notice } from '../ui';
import { expenseFileKey } from './expenseFileKey';
import { FilePreviews, type PreviewFile } from '../files/FilePreview';

/**
 * The receipt files of an expense: image thumbnails (click opens a viewer), PDF tiles (click opens a new tab)
 * and a download button for each; downloads use the session and keep the stored name.
 */
export function AttachmentList({
  expenseId,
  attachments,
}: {
  expenseId: string;
  attachments: readonly ExpenseAttachment[];
}) {
  const t = useT();
  const a = t.expenses.attachments;
  const download = usePdfAction();
  if (attachments.length === 0) return <p className="text-ink-muted">{a.none}</p>;
  const files: PreviewFile[] = attachments.map((file) => ({
    key: expenseFileKey(expenseId, file.n),
    fileName: file.fileName,
    contentType: file.contentType,
    load: () => downloadExpenseAttachment(expenseId, file.n),
  }));
  return (
    <div className="flex flex-col gap-2">
      {download.error !== undefined && <Notice tone="danger">{problemMessage(t, download.error)}</Notice>}
      <FilePreviews
        files={files}
        actions={(file) => {
          const size = attachments.find((x) => expenseFileKey(expenseId, x.n) === file.key)?.sizeBytes ?? 0;
          return (
            <>
              <span className="w-full text-sm text-ink-muted">
                {format(a.size, { size: Math.max(1, Math.round(size / 1024)) })}
              </span>
              <Button
                variant="secondary"
                disabled={download.pending}
                onClick={() => file.load && void download.run(file.load, file.fileName)}
              >
                {a.download}
              </Button>
            </>
          );
        }}
      />
    </div>
  );
}
