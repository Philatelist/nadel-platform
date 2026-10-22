import { useListBillingMonths, type BillingMonthSummary } from '@nadel/api-client';
import { Link } from 'react-router';
import { billingMonthLabel } from '../components/charges/billingMonth';
import { ChargesTabs } from '../components/charges/ChargesTabs';
import { MonthTotals } from '../components/charges/MonthTotals';
import { QueryBoundary } from '../components/QueryBoundary';
import { Badge, Money, PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

export function ChargesPage() {
  const t = useT();
  useDocumentTitle(t.billing.title);
  const query = useListBillingMonths();
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader title={t.billing.title} />
      <ChargesTabs />
      <QueryBoundary query={query} isEmpty={(months) => months.length === 0} emptyMessage={t.billing.empty}>
        {(months) => (
          <ul aria-label={t.billing.listCaption} className="flex flex-col gap-3">
            {months.map((month) => (
              <MonthCard key={month.month} month={month} />
            ))}
          </ul>
        )}
      </QueryBoundary>
    </div>
  );
}

function MonthCard({ month }: { month: BillingMonthSummary }) {
  const t = useT();
  return (
    <li className="flex flex-col gap-3 rounded-panel border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Link
          to={`/charges/months/${month.month}`}
          className="text-[17px] font-semibold text-accent underline"
        >
          {billingMonthLabel(month.month, t)}
        </Link>
        <Badge tone={month.status === 'CLOSED' ? 'neutral' : 'info'}>{t.billing.status[month.status]}</Badge>
      </div>
      <MonthTotals totals={month.totals} />
      <p className="flex justify-between gap-4 font-semibold">
        <span>{t.billing.total}</span>
        <Money amount={month.total} />
      </p>
    </li>
  );
}
