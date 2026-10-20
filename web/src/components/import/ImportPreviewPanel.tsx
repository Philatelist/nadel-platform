import type { ImportEntityCounts, ImportPreview, ImportProblem } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { Button, DataTable, Notice, Panel, type Column } from '../ui';
import { useLocale, useT } from '../../i18n';

/** How many problems the table shows; the rest is summarised as "and N more". */
export const maxShownProblems = 200;

const entities = ['plots', 'members', 'ownerships'] as const;

export function ImportPreviewPanel({ preview, onReset }: { preview: ImportPreview; onReset: () => void }) {
  const t = useT();
  const { locale } = useLocale();
  const s = t.import;
  const sheets: Readonly<Record<string, string>> = t.importSheets;
  const columnLabels: Readonly<Record<string, Readonly<Record<string, string>>>> = {
    PLOTS: t.importColumns.plots,
    MEMBERS: t.importColumns.members,
    OWNERSHIP: t.importColumns.ownership,
  };
  const messages: Readonly<Record<string, string>> = t.importProblem;

  const countRows = entities.map((key) => ({ key, label: s.entity[key], counts: preview.counts[key] }));
  const countColumns: Column<(typeof countRows)[number]>[] = [
    { key: 'entity', header: '', cell: (r) => r.label },
    ...(['created', 'updated', 'unchanged'] as const).map((k) => ({
      key: k,
      header: s[k],
      numeric: true,
      cell: (r: (typeof countRows)[number]) => (r.counts satisfies ImportEntityCounts)[k],
    })),
  ];

  const shown = preview.problems.slice(0, maxShownProblems);
  const hidden = preview.problemCount - shown.length;
  const problemColumns: Column<ImportProblem>[] = [
    { key: 'sheet', header: s.colSheet, cell: (p) => sheets[p.sheet] ?? p.sheet },
    { key: 'row', header: s.colRow, cell: (p) => (p.row === 0 ? '—' : p.row) },
    {
      key: 'column',
      header: s.colColumn,
      cell: (p) => (p.column ? (columnLabels[p.sheet]?.[p.column] ?? p.column) : '—'),
    },
    {
      key: 'message',
      header: s.colMessage,
      cell: (p) => format(messages[p.code] ?? t.importProblem.UNKNOWN, p.params ?? {}),
    },
  ];

  const time = new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(preview.expiresAt),
  );
  const hintId = 'import-run-hint';
  const hasProblems = preview.problemCount > 0;

  return (
    <Panel title={s.previewTitle}>
      <div className="flex flex-col gap-4">
        <div className="text-sm text-ink-muted">
          <p>{format(s.fileName, { name: preview.fileName })}</p>
          <p>{format(s.validUntil, { time })}</p>
        </div>
        <DataTable
          columns={countColumns}
          rows={countRows}
          rowKey={(r) => r.key}
          caption={s.countsCaption}
          emptyMessage=""
        />
        {hasProblems ? (
          <div className="flex flex-col gap-3">
            <Notice tone="warning">
              <p className="font-medium">{format(s.problemsTitle, { count: preview.problemCount })}</p>
              <p>{s.problemsHint}</p>
            </Notice>
            <DataTable
              columns={problemColumns}
              rows={shown}
              rowKey={(p) => `${p.sheet}:${p.row}:${p.column ?? ''}:${p.code}`}
              caption={s.problemsCaption}
              emptyMessage={s.noProblems}
            />
            {hidden > 0 && <p className="text-sm text-ink-muted">{format(s.andMore, { count: hidden })}</p>}
          </div>
        ) : preview.committable ? (
          <Notice tone="success">{s.ready}</Notice>
        ) : (
          <Notice tone="warning">{s.nothingToImport}</Notice>
        )}
        <div className="flex flex-wrap items-center gap-3">
          {/* The commit arrives in a later slice: the button stays disabled. */}
          <Button disabled aria-describedby={hintId}>
            {s.run}
          </Button>
          <Button variant="secondary" onClick={onReset}>
            {s.chooseAnother}
          </Button>
        </div>
        <p id={hintId} className="text-sm text-ink-muted">
          {preview.committable ? s.runUnavailable : s.runBlocked}
        </p>
      </div>
    </Panel>
  );
}
