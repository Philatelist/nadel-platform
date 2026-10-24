import { useState } from 'react';
import { downloadReport, useGetReport, useListReports, type ReportInfo } from '@nadel/api-client';
import { Link, useParams, useSearchParams } from 'react-router';
import { NoAccess } from '../components/NoAccess';
import { ReportFilterForm } from '../components/reports/ReportFilterForm';
import { ReportPreviewTable } from '../components/reports/ReportPreviewTable';
import { Button, ErrorState, LoadingState, Notice, PageHeader } from '../components/ui';
import { downloadBlob } from '../downloadBlob';
import { useLocale, useT } from '../i18n';
import { problemCode, problemMessage } from '../problem';
import {
  defaultValues,
  effectiveValues,
  reportFileName,
  toRequestParams,
  toSearch,
  valuesFromSearch,
} from '../reportFilters';
import { reportErrorMessage } from '../reportError';
import { reportTexts } from '../reportTexts';
import { useDocumentTitle } from '../useDocumentTitle';

export function ReportPage() {
  const t = useT();
  const { code } = useParams();
  const list = useListReports();
  useDocumentTitle(code === undefined ? t.reports.title : reportTexts(t, code)?.title);

  if (list.isPending) return <LoadingState message={t.state.loading} />;
  if (list.isError) {
    return <ErrorState message={problemMessage(t, list.error)} onRetry={() => void list.refetch()} />;
  }
  const report = list.data.find((candidate) => candidate.code === code);
  if (report === undefined || reportTexts(t, report.code) === undefined) return <NoAccess />;
  return <ReportView report={report} />;
}

function ReportView({ report }: { report: ReportInfo }) {
  const t = useT();
  const { locale } = useLocale();
  const [search, setSearch] = useSearchParams();
  const [downloadError, setDownloadError] = useState<unknown>(undefined);
  const [downloading, setDownloading] = useState(false);
  const texts = reportTexts(t, report.code);

  const chosen = valuesFromSearch(search, report.filters);
  const defaults = defaultValues(report.filters);
  const effective = effectiveValues(chosen, defaults);
  const params = toRequestParams(chosen);
  const query = useGetReport(report.code, params, { query: { retry: false } });
  const basis = report.filters.find((filter) => filter.basis !== undefined)?.basis;

  const download = async () => {
    setDownloading(true);
    setDownloadError(undefined);
    try {
      const blob = await downloadReport(report.code, { ...params, lang: locale });
      downloadBlob(blob, reportFileName(report.code, effective));
    } catch (error: unknown) {
      setDownloadError(error);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={texts?.title}
        description={texts?.description}
        actions={
          <>
            <Link to="/reports" className="text-accent underline">
              {t.reports.back}
            </Link>
            <Button
              variant="secondary"
              disabled={downloading || query.isError}
              onClick={() => void download()}
            >
              {downloading ? t.reports.downloading : t.reports.download}
            </Button>
          </>
        }
      />
      {basis !== undefined && <p className="text-sm text-ink-muted">{t.reports.basis[basis]}</p>}
      <ReportFilterForm
        key={`${report.code}?${toSearch(effective).toString()}`}
        report={report}
        initial={effective}
        onSubmit={(values) => setSearch(toSearch(values))}
      />
      {downloadError !== undefined && (
        <Notice tone="danger">
          {problemCode(downloadError) === undefined
            ? t.reports.downloadFailed
            : reportErrorMessage(t, downloadError)}
        </Notice>
      )}
      {query.isPending ? (
        <LoadingState message={t.state.loading} />
      ) : query.isError ? (
        <ErrorState message={reportErrorMessage(t, query.error)} onRetry={() => void query.refetch()} />
      ) : (
        <ReportPreviewTable preview={query.data} />
      )}
    </div>
  );
}
