import {
  getListAuditEntriesQueryKey,
  getListReportTemplatesQueryKey,
  getListReportTemplateVersionsQueryKey,
  ReportTemplateSlot,
  uploadReportTemplate,
  uploadReportTemplateVersion,
  type ReportTemplateUploadResult,
} from '@nadel/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent, type FormEvent } from 'react';
import { UserContent } from '../UserContent';
import { Button, buttonClass, Notice, TextArea, TextField } from '../ui';
import { useT } from '../../i18n';
import { reportTemplateErrorMessage, reportTemplateNameError } from '../../reportTemplateError';
import { validateReportTemplateFile, type ReportTemplateFileError } from '../../reportTemplateFile';

/** What the form uploads: a free template, the tax slot template, or a new version of a template. */
export type UploadTarget = { kind: 'new' } | { kind: 'tax' } | { kind: 'version'; id: string };

export function TemplateUploadForm({
  target,
  onUploaded,
  onCancel,
}: {
  target: UploadTarget;
  onUploaded: (result: ReportTemplateUploadResult) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const s = t.reportTemplates;
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<ReportTemplateFileError | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [nameError, setNameError] = useState<string | undefined>();
  const [showMissing, setShowMissing] = useState(false);
  const withName = target.kind === 'new';

  const upload = useMutation({
    mutationFn: (chosen: File) => {
      if (target.kind === 'version') return uploadReportTemplateVersion(target.id, { file: chosen });
      if (target.kind === 'tax') {
        return uploadReportTemplate({ file: chosen, name: s.tax.name, slot: ReportTemplateSlot.TAX });
      }
      const trimmed = description.trim();
      return uploadReportTemplate({
        file: chosen,
        name: name.trim(),
        ...(trimmed === '' ? {} : { description: trimmed }),
      });
    },
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: getListReportTemplatesQueryKey() });
      void queryClient.invalidateQueries({ queryKey: getListAuditEntriesQueryKey() });
      if (target.kind === 'version') {
        void queryClient.invalidateQueries({ queryKey: getListReportTemplateVersionsQueryKey(target.id) });
      }
      onUploaded(result);
    },
    onError: (error) => {
      setNameError(reportTemplateNameError(t, error));
      // The list may have changed under us (a version added elsewhere, a template archived).
      void queryClient.invalidateQueries({ queryKey: getListReportTemplatesQueryKey() });
    },
  });

  function onChoose(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files?.[0] ?? null;
    // Reset the input so choosing the same file again fires change.
    event.target.value = '';
    upload.reset();
    setFileError(chosen ? (validateReportTemplateFile(chosen) ?? null) : null);
    setFile(chosen);
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setShowMissing(true);
    setNameError(undefined);
    if (file === null || fileError !== null || (withName && name.trim() === '')) return;
    upload.mutate(file);
  }

  const nameMissing = withName && showMissing && name.trim() === '' ? s.form.nameRequired : undefined;
  const serverFailure = upload.isError && nameError === undefined;

  return (
    <form
      onSubmit={onSubmit}
      className="flex max-w-xl flex-col gap-3 rounded-control border border-line bg-surface p-4"
    >
      {withName && (
        <>
          <TextField
            label={s.form.name}
            value={name}
            maxLength={200}
            error={nameError ?? nameMissing}
            onChange={(event) => {
              setName(event.target.value);
              setNameError(undefined);
            }}
          />
          <TextArea
            label={s.form.description}
            value={description}
            maxLength={500}
            onChange={(event) => setDescription(event.target.value)}
          />
        </>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <label
          className={`${buttonClass('secondary')} cursor-pointer focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent`}
        >
          <input
            type="file"
            className="sr-only"
            aria-label={s.form.file}
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={onChoose}
          />
          {s.form.chooseFile}
        </label>
        <span aria-live="polite" className="text-sm text-ink-muted">
          {file ? <UserContent>{file.name}</UserContent> : s.form.noFileChosen}
        </span>
      </div>
      <p className="text-sm text-ink-muted">{s.form.file}</p>
      {showMissing && file === null && <Notice tone="danger">{s.form.fileRequired}</Notice>}
      {fileError && <Notice tone="danger">{s.fileErrors[fileError]}</Notice>}
      {serverFailure && <Notice tone="danger">{reportTemplateErrorMessage(t, upload.error)}</Notice>}
      <div className="flex gap-2">
        <Button type="submit" disabled={upload.isPending}>
          {upload.isPending ? s.form.uploading : s.form.submit}
        </Button>
        <Button variant="secondary" onClick={onCancel}>
          {s.cancel}
        </Button>
      </div>
    </form>
  );
}
