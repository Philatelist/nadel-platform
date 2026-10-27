import {
  archiveReportTemplate,
  getListAuditEntriesQueryKey,
  getListReportTemplatesQueryKey,
  useListReportTemplates,
  type ReportTemplate,
  type ReportTemplateUploadResult,
} from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { QueryBoundary } from '../components/QueryBoundary';
import { FieldCatalog } from '../components/reportTemplates/FieldCatalog';
import { GenerateDialog } from '../components/reportTemplates/GenerateDialog';
import { TaxSlotEmpty } from '../components/reportTemplates/TaxSlotCard';
import { TemplateRow } from '../components/reportTemplates/TemplateList';
import { TemplateUploadForm, type UploadTarget } from '../components/reportTemplates/TemplateUploadForm';
import { UserContent } from '../components/UserContent';
import { Button, Notice, PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { reportTemplateErrorMessage } from '../reportTemplateError';
import { useDocumentTitle } from '../useDocumentTitle';

type OpenForm = { for: string; target: UploadTarget } | null;

function formKey(target: UploadTarget): string {
  return target.kind === 'version' ? `version:${target.id}` : target.kind;
}

export function ReportTemplatesPage() {
  const t = useT();
  const s = t.reportTemplates;
  useDocumentTitle(s.title);
  const queryClient = useQueryClient();
  const query = useListReportTemplates();
  const [form, setForm] = useState<OpenForm>(null);
  const [uploaded, setUploaded] = useState<ReportTemplateUploadResult | null>(null);
  const [openVersions, setOpenVersions] = useState<ReadonlySet<string>>(new Set());
  const [archiving, setArchiving] = useState<ReportTemplate | null>(null);
  const [generating, setGenerating] = useState<ReportTemplate | null>(null);

  const archive = useMutation({
    mutationFn: (id: string) => archiveReportTemplate(id),
    onSuccess: () => {
      setArchiving(null);
      void queryClient.invalidateQueries({ queryKey: getListAuditEntriesQueryKey() });
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: getListReportTemplatesQueryKey() });
    },
  });

  const archiveText = s.archiveText.split('{name}');

  function show(target: UploadTarget) {
    setUploaded(null);
    setForm({ for: formKey(target), target });
  }

  function formFor(target: UploadTarget) {
    if (form?.for !== formKey(target)) return null;
    return (
      <TemplateUploadForm
        target={form.target}
        onCancel={() => setForm(null)}
        onUploaded={(result) => {
          setForm(null);
          setUploaded(result);
        }}
      />
    );
  }

  function toggleVersions(id: string) {
    setOpenVersions((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  function row(template: ReportTemplate) {
    const id = template.id;
    if (id === undefined) return null;
    return (
      <TemplateRow
        key={id}
        template={template}
        versionsOpen={openVersions.has(id)}
        onToggleVersions={() => toggleVersions(id)}
        onUploadVersion={() => show({ kind: 'version', id })}
        onGenerate={() => setGenerating(template)}
        onArchive={() => {
          archive.reset();
          setArchiving(template);
        }}
      >
        {formFor({ kind: 'version', id })}
      </TemplateRow>
    );
  }

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={s.title} description={s.intro} />
      <Link to="/reports" className="text-accent underline">
        {s.back}
      </Link>
      {uploaded && (
        <div className="flex flex-col gap-2">
          <Notice tone="success">
            {s.uploaded} <UserContent>{uploaded.template.name ?? ''}</UserContent>,{' '}
            {format(s.version, { n: uploaded.template.latestVersion?.versionNo ?? 1 })}
          </Notice>
          {uploaded.warnings.map((warning) => (
            <Notice key={warning} tone="warning">
              {s.warnings[warning]}
            </Notice>
          ))}
        </div>
      )}
      <QueryBoundary query={query}>
        {(entries) => {
          const tax = entries.find((entry) => entry.slot === 'TAX' && entry.status !== 'ARCHIVED');
          const others = entries.filter((entry) => entry !== tax);
          return (
            <>
              {tax && tax.status === 'ACTIVE' ? (
                <ul aria-label={s.tax.title} className="flex flex-col gap-3">
                  {row(tax)}
                </ul>
              ) : (
                <TaxSlotEmpty onUpload={() => show({ kind: 'tax' })}>{formFor({ kind: 'tax' })}</TaxSlotEmpty>
              )}
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-[17px] font-semibold">{s.listTitle}</h2>
                <Button variant="secondary" onClick={() => show({ kind: 'new' })}>
                  {s.add}
                </Button>
              </div>
              {formFor({ kind: 'new' })}
              {others.length === 0 ? (
                <p className="text-ink-muted">{s.empty}</p>
              ) : (
                <ul aria-label={s.listTitle} className="flex flex-col gap-3">
                  {others.map(row)}
                </ul>
              )}
            </>
          );
        }}
      </QueryBoundary>
      <FieldCatalog />
      {generating?.id !== undefined && (
        <GenerateDialog
          template={{ id: generating.id, name: generating.name ?? '' }}
          onClose={() => setGenerating(null)}
        />
      )}
      {archiving?.id !== undefined && (
        <ConfirmDialog
          title={s.archiveTitle}
          message={
            <>
              {archiveText[0]}
              <UserContent>{archiving.name ?? ''}</UserContent>
              {archiveText[1]}
              {archive.isError && (
                <span className="mt-2 block text-danger" role="alert">
                  {reportTemplateErrorMessage(t, archive.error)}
                </span>
              )}
            </>
          }
          confirmLabel={s.archiveConfirm}
          busy={archive.isPending}
          onConfirm={() => archive.mutate(archiving.id as string)}
          onCancel={() => setArchiving(null)}
        />
      )}
    </div>
  );
}
