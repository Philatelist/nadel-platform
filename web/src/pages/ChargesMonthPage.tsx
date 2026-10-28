import { useGetBillingMonth, type BillingMonthDetails } from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { billingMonthLabel } from '../components/charges/billingMonth';
import { ChargesTabs } from '../components/charges/ChargesTabs';
import { CloseMonthDialog } from '../components/charges/CloseMonthDialog';
import { MonthInvoicesPanel } from '../components/invoices/MonthInvoicesPanel';
import { MonthChecklist } from '../components/charges/MonthChecklist';
import { RunPreviewDialog } from '../components/charges/RunPreviewDialog';
import { MonthTotals } from '../components/charges/MonthTotals';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import { Badge, Button, PageHeader, Panel } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { reportPath } from '../reportFilters';
import { useDocumentTitle } from '../useDocumentTitle';

export function ChargesMonthPage() {
  const t = useT();
  const { ym = '' } = useParams();
  useDocumentTitle(billingMonthLabel(ym, t));
  const query = useGetBillingMonth(ym);
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <ChargesTabs />
      <Link to="/charges" className="text-accent underline">
        {t.billing.back}
      </Link>
      <QueryBoundary query={query}>{(data) => <MonthView month={data} />}</QueryBoundary>
    </div>
  );
}

function MonthView({ month }: { month: BillingMonthDetails }) {
  const t = useT();
  const { locale } = useLocale();
  const canClose = useCan('PERIODS_CLOSE');
  const canRun = useCan('CHARGES_RUN');
  const canViewReports = useCan('REPORTS_VIEW');
  const [closing, setClosing] = useState(false);
  const [running, setRunning] = useState(false);
  const closed = month.status === 'CLOSED';
  return (
    <>
      <PageHeader
        title={billingMonthLabel(month.month, t)}
        badge={<Badge tone={closed ? 'neutral' : 'info'}>{t.billing.status[month.status]}</Badge>}
        description={
          closed && month.closedAt ? (
            <>
              {format(t.billing.closedAt, { date: formatDay(month.closedAt.slice(0, 10), locale) })}
              {month.closedByName && (
                <>
                  {' '}
                  {t.billing.closedBy} <UserContent>{month.closedByName}</UserContent>
                </>
              )}
            </>
          ) : undefined
        }
        actions={
          canViewReports || (!closed && (canRun || canClose)) ? (
            <>
              {canViewReports && (
                <Link
                  to={reportPath('charges-by-month', { fromMonth: month.month, toMonth: month.month })}
                  className="text-accent underline"
                >
                  {t.reports.monthReportLink}
                </Link>
              )}
              {!closed && canRun && <Button onClick={() => setRunning(true)}>{t.billing.run.action}</Button>}
              {!closed && canClose && (
                <Button variant="secondary" onClick={() => setClosing(true)}>
                  {t.billing.close.action}
                </Button>
              )}
            </>
          ) : undefined
        }
      />
      <Panel title={t.billing.totalsTitle}>
        <MonthTotals totals={month.totals} total={month.total} received={month.received} />
      </Panel>
      <Panel title={t.billing.checklist.title}>
        <MonthChecklist checklist={month.checklist} />
      </Panel>
      <MonthInvoicesPanel month={month.month} />
      {running && <RunPreviewDialog month={month.month} onClose={() => setRunning(false)} />}
      {closing && (
        <CloseMonthDialog
          month={month.month}
          checklist={month.checklist}
          onDone={() => setClosing(false)}
          onClose={() => setClosing(false)}
        />
      )}
    </>
  );
}
