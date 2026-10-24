import { downloadReportTemplateVersion, useListReportTemplateVersions } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { useMutation } from '@tanstack/react-query';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent } from '../UserContent';
import { Button, Notice } from '../ui';
import { downloadBlob } from '../../downloadBlob';
import { useT } from '../../i18n';
import { UploadedLine } from './UploadedLine';

/** The versions of one template, newest first, each with a download button. */
export function TemplateVersions({ id }: { id: string }) {
  const t = useT();
  const s = t.reportTemplates;
  const query = useListReportTemplateVersions(id);
  const download = useMutation({
    mutationFn: async (version: { id: string; fileName: string }) => ({
      blob: await downloadReportTemplateVersion(id, version.id),
      fileName: version.fileName,
    }),
    onSuccess: ({ blob, fileName }) => downloadBlob(blob, fileName),
  });
  return (
    <div className="flex flex-col gap-2" aria-label={s.versionsOf}>
      {download.isError && <Notice tone="danger">{s.downloadFailed}</Notice>}
      <QueryBoundary query={query} isEmpty={(versions) => versions.length === 0} emptyMessage={s.noVersions}>
        {(versions) => (
          <ul className="flex flex-col gap-2">
            {versions.map((version) => (
              <li key={version.id} className="flex flex-wrap items-center justify-between gap-2">
                <span>
                  <span className="font-medium">{format(s.version, { n: version.versionNo })}</span>
                  {', '}
                  <UserContent>{version.fileName}</UserContent>
                  <span className="text-ink-muted">
                    {' · '}
                    <UploadedLine at={version.uploadedAt} by={version.uploadedByName} />
                  </span>
                </span>
                <Button
                  variant="secondary"
                  disabled={download.isPending}
                  onClick={() => download.mutate({ id: version.id, fileName: version.fileName })}
                >
                  {s.download}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </QueryBoundary>
    </div>
  );
}
