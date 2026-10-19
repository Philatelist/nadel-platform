import { downloadImportTemplate } from '@nadel/api-client';
import { useMutation } from '@tanstack/react-query';
import { Button, Notice, PageHeader, Panel } from '../components/ui';
import { downloadBlob } from '../downloadBlob';
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
      {/* Step 2 (upload and preview) arrives in the next slice. */}
    </div>
  );
}
