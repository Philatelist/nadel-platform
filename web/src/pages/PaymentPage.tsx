import { useGetPayment, type PaymentAllocation, type PaymentGroupPart } from '@nadel/api-client';
import { format, formatDateTime, formatDay } from '@nadel/shared';
import { useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { ChargeTypeName } from '../components/ChargeTypeName';
import { billingMonthLabel } from '../components/charges/billingMonth';
import { ReallocateDialog } from '../components/payments/ReallocateDialog';
import { ReversePaymentDialog } from '../components/payments/ReversePaymentDialog';
import { servicePeriodLabel } from '../components/payments/paymentLabels';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import { Badge, Button, DataTable, Money, PageHeader, Panel, type Column } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

/** One payment, read-only: its details, what it was allocated to, who entered it and when. */
export function PaymentPage() {
  const t = useT();
  const { locale } = useLocale();
  const { id = '' } = useParams();
  const query = useGetPayment(id);
  const c = t.payments.card;
  useDocumentTitle(c.title);
  const canReverse = useCan('PAYMENTS_REVERSE');
  const canEnter = useCan('PAYMENTS_ENTER');
  const [dialog, setDialog] = useState<'reverse' | 'reallocate'>();

  const columns: Column<PaymentAllocation>[] = [
    {
      key: 'charge',
      header: c.charge,
      cell: (a) =>
        a.system && a.origin === 'PREPAYMENT_AUTO' ? (
          <span>
            <ChargeTypeName type={a.chargeType} />
            <span className="block text-sm text-ink-muted">{c.fromPrepayment}</span>
          </span>
        ) : a.releaseCause ? (
          <span>
            <ChargeTypeName type={a.chargeType} />
            <span className="block text-sm text-ink-muted">
              {c.releaseCause[a.releaseCause] ?? c.released}
            </span>
          </span>
        ) : (
          <ChargeTypeName type={a.chargeType} />
        ),
    },
    {
      key: 'period',
      header: c.period,
      cell: (a) => servicePeriodLabel(a.serviceFrom, a.serviceTo, t, locale),
    },
    { key: 'amount', header: c.amountColumn, numeric: true, cell: (a) => <Money amount={a.amount} /> },
  ];

  const groupColumns: Column<PaymentGroupPart>[] = [
    {
      key: 'plot',
      header: c.groupPlot,
      cell: (g) => (
        <span className="flex flex-wrap items-center gap-2">
          <Link to={`/payments/${g.id}`} className="font-medium text-accent underline">
            <UserContent>{g.plotNumber}</UserContent>
          </Link>
          {g.reversed && <Badge tone="neutral">{t.payments.reversed}</Badge>}
        </span>
      ),
    },
    { key: 'amount', header: c.groupAmount, numeric: true, cell: (g) => <Money amount={g.amount} /> },
  ];

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <QueryBoundary query={query}>
        {(p) => {
          const rows: [string, ReactNode][] = [
            [
              c.plot,
              <Link key="plot" to={`/plots/${p.plotId}`} className="text-accent underline">
                <UserContent>{p.plotNumber}</UserContent>
              </Link>,
            ],
            [c.receivedOn, formatDay(p.receivedOn, locale)],
            [c.billingMonth, billingMonthLabel(p.billingMonth, t)],
            [c.source, t.payments.source[p.source]],
          ];
          if (p.bankReference) {
            rows.push([c.bankReference, <UserContent key="ref">{p.bankReference}</UserContent>]);
          }
          if (p.payer) rows.push([c.payer, <UserContent key="payer">{p.payer.shortName}</UserContent>]);
          if (p.payerNote) rows.push([c.payerNote, <UserContent key="note">{p.payerNote}</UserContent>]);
          rows.push(
            [c.amount, <Money key="amount" amount={p.amount} />],
            [c.allocated, <Money key="allocated" amount={p.allocated} />],
            [c.prepayment, <Money key="prepayment" amount={p.prepayment} />],
          );
          if (p.reversedBy) {
            rows.push(
              [c.reversalDate, formatDateTime(p.reversedBy.createdAt, locale)],
              [c.reversalMonth, billingMonthLabel(p.reversedBy.billingMonth, t)],
              [c.reversalReason, <UserContent key="reason">{p.reversedBy.reason}</UserContent>],
            );
          }
          const entered = formatDateTime(p.createdAt, locale);
          return (
            <>
              <PageHeader
                title={
                  p.receiptNumber !== undefined ? (
                    format(c.titleReceipt, { number: p.receiptNumber })
                  ) : p.bankReference ? (
                    <UserContent>{p.bankReference}</UserContent>
                  ) : (
                    c.titleBank
                  )
                }
                badge={p.reversed && <Badge tone="neutral">{t.payments.reversed}</Badge>}
                actions={
                  <>
                    {p.reallocatable && canEnter && (
                      <Button variant="secondary" onClick={() => setDialog('reallocate')}>
                        {t.payments.reallocate.action}
                      </Button>
                    )}
                    {p.reversible && canReverse && (
                      <Button variant="danger" onClick={() => setDialog('reverse')}>
                        {t.payments.reverse.action}
                      </Button>
                    )}
                    <Link to="/payments" className="text-accent underline">
                      {t.payments.back}
                    </Link>
                  </>
                }
              />
              <Panel>
                <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-[minmax(10rem,max-content)_1fr]">
                  {rows.map(([label, value]) => (
                    <div key={label} className="contents">
                      <dt className="text-sm font-medium text-ink-muted sm:text-[15px] sm:font-normal">
                        {label}
                      </dt>
                      <dd className="mb-2 break-words whitespace-pre-line sm:mb-0">{value}</dd>
                    </div>
                  ))}
                </dl>
              </Panel>
              <Panel title={c.allocationsTitle}>
                <DataTable
                  columns={columns}
                  rows={p.allocations}
                  rowKey={(a) => a.id}
                  caption={c.allocationsCaption}
                  emptyMessage={c.noAllocations}
                />
              </Panel>
              {p.groupParts.length > 0 && (
                <Panel title={c.groupTitle}>
                  <DataTable
                    columns={groupColumns}
                    rows={p.groupParts}
                    rowKey={(g) => g.id}
                    caption={c.groupCaption}
                    emptyMessage=""
                  />
                </Panel>
              )}
              {dialog === 'reverse' && (
                <ReversePaymentDialog
                  payment={p}
                  onDone={() => setDialog(undefined)}
                  onClose={() => setDialog(undefined)}
                />
              )}
              {dialog === 'reallocate' && (
                <ReallocateDialog
                  payment={p}
                  onDone={() => setDialog(undefined)}
                  onClose={() => setDialog(undefined)}
                />
              )}
              <p className="text-sm text-ink-muted">
                {c.entered}{' '}
                {p.createdByName && (
                  <>
                    <UserContent>{p.createdByName}</UserContent>
                    {', '}
                  </>
                )}
                {entered}
              </p>
            </>
          );
        }}
      </QueryBoundary>
    </div>
  );
}
