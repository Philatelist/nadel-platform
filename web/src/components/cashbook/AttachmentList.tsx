import { downloadExpenseAttachment, type ExpenseAttachment } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useT } from '../../i18n';
import { usePdfAction } from '../../hooks/usePdfAction';
import { problemMessage } from '../../problem';
import { UserContent } from '../UserContent';
import { Button, Notice } from '../ui';

/** The receipt files of an expense; each is fetched with the session and saved under its stored name. */
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
  return (
    <div className="flex flex-col gap-2">
      {download.error !== undefined && <Notice tone="danger">{problemMessage(t, download.error)}</Notice>}
      <ul className="flex flex-col gap-2">
        {attachments.map((file) => (
          <li key={file.n} className="flex flex-wrap items-center gap-3">
            <span className="min-w-0 break-all">
              <UserContent>{file.fileName}</UserContent>
            </span>
            <span className="text-sm text-ink-muted">
              {format(a.size, { size: Math.max(1, Math.round(file.sizeBytes / 1024)) })}
            </span>
            <Button
              variant="secondary"
              disabled={download.pending}
              onClick={() =>
                void download.run(() => downloadExpenseAttachment(expenseId, file.n), file.fileName)
              }
            >
              {a.download}
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
