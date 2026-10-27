import type { ReportTemplate } from '@nadel/api-client';
import { format } from '@nadel/shared';
import type { ReactNode } from 'react';
import { UserContent } from '../UserContent';
import { Badge, Button } from '../ui';
import { useT } from '../../i18n';
import { TemplateVersions } from './TemplateVersions';
import { UploadedLine } from './UploadedLine';

/** One template: facts, and the actions allowed for its status (an archived template is read-only). */
export function TemplateRow({
  template,
  versionsOpen,
  onToggleVersions,
  onUploadVersion,
  onArchive,
  onGenerate,
  manage = true,
  children,
}: {
  template: ReportTemplate;
  versionsOpen: boolean;
  onToggleVersions: () => void;
  onUploadVersion: () => void;
  onArchive: () => void;
  /** Present for an active template the user may generate a file from. */
  onGenerate?: () => void;
  /** False hides upload, versions and archive (a user who may only generate files). */
  manage?: boolean;
  children?: ReactNode;
}) {
  const s = useT().reportTemplates;
  const archived = template.status === 'ARCHIVED';
  const latest = template.latestVersion;
  return (
    <li className="flex flex-col gap-2 rounded-panel border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-[16px] font-semibold">
          <UserContent>{template.name ?? ''}</UserContent>
        </h3>
        <Badge tone={archived ? 'neutral' : 'success'}>{s.status[template.status]}</Badge>
      </div>
      {template.description && (
        <p className="text-ink-muted">
          <UserContent>{template.description}</UserContent>
        </p>
      )}
      {latest && (
        <p className="text-sm text-ink-muted">
          {format(s.version, { n: latest.versionNo })}, <UserContent>{latest.fileName}</UserContent>
          {' · '}
          <UploadedLine at={latest.uploadedAt} by={latest.uploadedByName} />
          {' · '}
          {format(s.versionCount, { count: template.versionCount })}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {!archived && onGenerate && <Button onClick={onGenerate}>{s.generate.button}</Button>}
        {manage && !archived && (
          <Button variant="secondary" onClick={onUploadVersion}>
            {s.uploadVersion}
          </Button>
        )}
        {manage && (
          <Button variant="secondary" aria-expanded={versionsOpen} onClick={onToggleVersions}>
            {versionsOpen ? s.hideVersions : s.versions}
          </Button>
        )}
        {manage && !archived && (
          <Button variant="danger" onClick={onArchive}>
            {s.archive}
          </Button>
        )}
      </div>
      {children}
      {versionsOpen && template.id !== undefined && <TemplateVersions id={template.id} />}
    </li>
  );
}
