import {
  downloadImportTemplate,
  getListAuditEntriesQueryKey,
  getListMembersQueryKey,
  getListPlotsQueryKey,
  uploadImport,
  commitImport,
  type ImportPreview,
  type ImportResult,
} from '@nadel/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { ImportPreviewPanel } from '../components/import/ImportPreviewPanel';
import { ImportResultPanel } from '../components/import/ImportResultPanel';
import { Button, buttonClass, Notice, PageHeader, Panel } from '../components/ui';
import { downloadBlob } from '../downloadBlob';
import { importErrorMessage } from '../importError';
import { validateImportFile, type ImportFileError } from '../importFile';
import { useT } from '../i18n';
import { problemMessage } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

export function ImportPage() {
  const t = useT();
  const s = t.import;
  useDocumentTitle(s.title);

  const template = useMutation({
    mutationFn: () => downloadImportTemplate(),
    onSuccess: (blob) => downloadBlob(blob, s.templateFileName),
  });

  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<ImportFileError | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const queryClient = useQueryClient();

  const commit = useMutation({
    mutationFn: (id: string) => commitImport(id),
    onSuccess: (done) => {
      setResult(done);
      setPreview(null);
      void queryClient.invalidateQueries({ queryKey: getListPlotsQueryKey() });
      void queryClient.invalidateQueries({ queryKey: getListMembersQueryKey() });
      void queryClient.invalidateQueries({ queryKey: getListAuditEntriesQueryKey() });
    },
  });

  const upload = useMutation({
    mutationFn: (chosen: File) => uploadImport({ file: chosen }),
    onSuccess: setPreview,
  });

  function onChoose(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files?.[0] ?? null;
    // Reset the input so choosing the same file again fires change.
    event.target.value = '';
    upload.reset();
    commit.reset();
    setPreview(null);
    setResult(null);
    setFileError(chosen ? (validateImportFile(chosen) ?? null) : null);
    setFile(chosen);
  }

  function reset() {
    upload.reset();
    commit.reset();
    setPreview(null);
    setResult(null);
    setFile(null);
    setFileError(null);
  }

  const canCheck = file !== null && fileError === null && !upload.isPending;

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={s.title} description={s.description} />
      <Panel title={s.templateTitle}>
        <div className="flex max-w-xl flex-col gap-3">
          <p className="text-sm text-ink-muted">{s.templateHint}</p>
          {template.isError && <Notice tone="danger">{problemMessage(t, template.error)}</Notice>}
          <div>
            <Button type="button" disabled={template.isPending} onClick={() => template.mutate()}>
              {template.isPending ? s.downloading : s.downloadTemplate}
            </Button>
          </div>
        </div>
      </Panel>
      <Panel title={s.uploadTitle}>
        <div className="flex max-w-xl flex-col gap-3">
          <p className="text-sm text-ink-muted">{s.uploadHint}</p>
          <div className="flex flex-wrap items-center gap-3">
            <label
              className={`${buttonClass('secondary')} cursor-pointer focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent`}
            >
              <input
                type="file"
                className="sr-only"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={onChoose}
              />
              {s.chooseFile}
            </label>
            <span aria-live="polite" className="text-sm text-ink-muted">
              {file ? file.name : s.noFileChosen}
            </span>
          </div>
          {fileError && <Notice tone="danger">{t.importError[fileError]}</Notice>}
          {upload.isError && <Notice tone="danger">{importErrorMessage(t, upload.error)}</Notice>}
          <div>
            <Button type="button" disabled={!canCheck} onClick={() => file && upload.mutate(file)}>
              {upload.isPending ? s.checking : s.check}
            </Button>
          </div>
        </div>
      </Panel>
      {preview && (
        <ImportPreviewPanel
          preview={preview}
          onReset={reset}
          onCommit={() => commit.mutate(preview.id)}
          committing={commit.isPending}
          commitError={commit.isError ? commit.error : undefined}
        />
      )}
      {result && <ImportResultPanel result={result} onReset={reset} />}
    </div>
  );
}
