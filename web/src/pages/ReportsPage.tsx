import { useListReports } from '@nadel/api-client';
import { Link } from 'react-router';
import { GenerateTemplates } from '../components/reportTemplates/GenerateTemplates';
import { QueryBoundary } from '../components/QueryBoundary';
import { PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { reportTexts } from '../reportTexts';
import { useDocumentTitle } from '../useDocumentTitle';

export function ReportsPage() {
  const t = useT();
  useDocumentTitle(t.reports.title);
  const query = useListReports();
  const canManageTemplates = useCan('REPORT_TEMPLATES_MANAGE');
  const canView = useCan('REPORTS_VIEW');
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={t.reports.title} description={t.reports.intro} />
      <QueryBoundary
        query={query}
        isEmpty={(reports) => reports.every((report) => reportTexts(t, report.code) === undefined)}
        emptyMessage={t.reports.empty}
      >
        {(reports) => (
          <ul aria-label={t.reports.title} className="grid gap-3 md:grid-cols-2">
            {reports.map((report) => {
              const texts = reportTexts(t, report.code);
              if (texts === undefined) return null;
              return (
                <li
                  key={report.code}
                  className="flex flex-col gap-2 rounded-panel border border-line bg-surface p-4"
                >
                  <Link
                    to={`/reports/${report.code}`}
                    className="text-[17px] font-semibold text-accent underline"
                  >
                    {texts.title}
                  </Link>
                  <p className="text-ink-muted">{texts.description}</p>
                </li>
              );
            })}
          </ul>
        )}
      </QueryBoundary>
      {canView && !canManageTemplates && <GenerateTemplates />}
      {canManageTemplates && (
        <section className="flex flex-col gap-2 rounded-panel border border-line bg-surface p-4">
          <Link to="/reports/templates" className="text-[17px] font-semibold text-accent underline">
            {t.reportTemplates.cardTitle}
          </Link>
          <p className="text-ink-muted">{t.reportTemplates.cardText}</p>
        </section>
      )}
    </div>
  );
}
