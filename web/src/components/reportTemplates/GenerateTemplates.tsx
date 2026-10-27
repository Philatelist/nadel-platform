import { useListReportTemplates, type ReportTemplate } from '@nadel/api-client';
import { useState } from 'react';
import { QueryBoundary } from '../QueryBoundary';
import { useT } from '../../i18n';
import { GenerateDialog } from './GenerateDialog';
import { TemplateRow } from './TemplateList';

const noop = () => {};

/** The active templates with a Generate button, for a user who may build reports but not manage templates. */
export function GenerateTemplates() {
  const s = useT().reportTemplates;
  const query = useListReportTemplates();
  const [generating, setGenerating] = useState<ReportTemplate | null>(null);
  return (
    <QueryBoundary query={query}>
      {(entries) => {
        const active = entries.filter((entry) => entry.status === 'ACTIVE' && entry.id !== undefined);
        if (active.length === 0) return null;
        return (
          <section aria-label={s.generate.listTitle} className="flex flex-col gap-2">
            <h2 className="text-[17px] font-semibold">{s.generate.listTitle}</h2>
            <p className="text-ink-muted">{s.generate.listIntro}</p>
            <ul className="flex flex-col gap-3">
              {active.map((template) => (
                <TemplateRow
                  key={template.id}
                  template={template}
                  manage={false}
                  versionsOpen={false}
                  onToggleVersions={noop}
                  onUploadVersion={noop}
                  onArchive={noop}
                  onGenerate={() => setGenerating(template)}
                />
              ))}
            </ul>
            {generating?.id !== undefined && (
              <GenerateDialog
                template={{ id: generating.id, name: generating.name ?? '' }}
                onClose={() => setGenerating(null)}
              />
            )}
          </section>
        );
      }}
    </QueryBoundary>
  );
}
