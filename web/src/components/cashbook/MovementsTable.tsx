import {
  reverseHandover,
  reverseOtherIncome,
  useListAccountMovements,
  type AccountMovement,
} from '@nadel/api-client';
import { format, formatDay } from '@nadel/shared';
import { keepPreviousData, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';
import { Pagination } from '../Pagination';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent } from '../UserContent';
import { Badge, Button, DataTable, Money, TextField, type Column } from '../ui';
import { invalidateCashbook } from './cashbookInvalidation';
import { ReasonDialog } from './ReasonDialog';

const PAGE_SIZE = 100;

/** The journal of one money account for a period; without dates the server shows this month up to today. */
export function MovementsTable({ accountId }: { accountId: string }) {
  const t = useT();
  const { locale } = useLocale();
  const m = t.cashbook.movements;
  const canApprove = useCan('CASHBOOK_APPROVE');
  const queryClient = useQueryClient();
  const [reversing, setReversing] = useState<AccountMovement>();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const query = useListAccountMovements(
    accountId,
    { from: from || undefined, to: to || undefined, page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );

  const columns: Column<AccountMovement>[] = [
    { key: 'date', header: m.columns.date, cell: (row) => formatDay(row.date, locale) },
    {
      key: 'kind',
      header: m.columns.kind,
      cell: (row) => (
        <span className="flex flex-wrap items-center gap-2">
          {t.cashbook.kind[row.kind]}
          {row.reversesMovementId !== undefined && <Badge tone="neutral">{m.reversal}</Badge>}
          {row.reversed && <Badge tone="neutral">{t.payments.reversed}</Badge>}
          {row.beforeOpening && <Badge tone="warning">{m.beforeOpening}</Badge>}
        </span>
      ),
    },
    {
      key: 'details',
      header: m.columns.details,
      cell: (row) => (
        <span className="flex flex-col gap-0.5">
          {(row.receiptNumber !== undefined || row.documentNumber !== undefined) && (
            <span>
              {row.plotId !== undefined && row.source.type === 'PAYMENT' ? (
                <Link to={`/payments/${row.source.id}`} className="text-accent underline">
                  {row.receiptNumber !== undefined ? (
                    format(t.payments.receiptNumber, { number: row.receiptNumber })
                  ) : (
                    <UserContent>{row.documentNumber ?? ''}</UserContent>
                  )}
                </Link>
              ) : row.receiptNumber !== undefined ? (
                format(t.payments.receiptNumber, { number: row.receiptNumber })
              ) : (
                <UserContent>{row.documentNumber ?? ''}</UserContent>
              )}
            </span>
          )}
          {row.reason !== undefined && (
            <span className="text-ink-muted">
              <UserContent>{row.reason}</UserContent>
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'amount',
      header: m.columns.amount,
      numeric: true,
      cell: (row) => <Money amount={row.amount} signed tone="auto" />,
    },
    {
      key: 'createdBy',
      header: m.columns.createdBy,
      cell: (row) =>
        row.createdByName !== undefined ? <UserContent>{row.createdByName}</UserContent> : null,
    },
    {
      key: 'actions',
      header: m.columns.actions,
      cell: (row) => {
        if (row.source.type === 'EXPENSE') {
          return (
            <Link to={`/cashbook/expenses/${row.source.id}`} className="text-accent underline">
              {t.cashbook.reverse.expense}
            </Link>
          );
        }
        if (row.reversesMovementId !== undefined || row.reversed) return null;
        if (canApprove && (row.source.type === 'HANDOVER' || row.source.type === 'OTHER_INCOME')) {
          return (
            <Button variant="secondary" onClick={() => setReversing(row)}>
              {t.cashbook.reverse.action}
            </Button>
          );
        }
        return null;
      },
    },
  ];

  return (
    <section className="flex flex-col gap-3" aria-label={m.title}>
      <h3 className="text-lg font-semibold text-ink">{m.title}</h3>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <TextField
          label={m.from}
          type="date"
          value={from}
          onChange={(e) => {
            setFrom(e.target.value);
            setPage(0);
          }}
        />
        <TextField
          label={m.to}
          type="date"
          value={to}
          onChange={(e) => {
            setTo(e.target.value);
            setPage(0);
          }}
        />
      </div>
      <QueryBoundary query={query}>
        {(data) => (
          <>
            <p className="text-[15px] text-ink-muted">
              {format(m.period, { from: formatDay(data.from, locale), to: formatDay(data.to, locale) })}
            </p>
            <dl className="flex flex-wrap gap-x-8 gap-y-1 text-[15px]">
              {data.openingBalance !== undefined && (
                <div className="flex gap-2">
                  <dt className="text-ink-muted">{m.openingBalance}</dt>
                  <dd>
                    <Money amount={data.openingBalance} />
                  </dd>
                </div>
              )}
              {data.closingBalance !== undefined && (
                <div className="flex gap-2">
                  <dt className="text-ink-muted">{m.closingBalance}</dt>
                  <dd>
                    <Money amount={data.closingBalance} tone={data.negative ? 'danger' : 'default'} />
                  </dd>
                </div>
              )}
            </dl>
            <DataTable
              columns={columns}
              rows={data.items}
              rowKey={(row) => row.id}
              caption={m.title}
              emptyMessage={m.empty}
            />
            <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
          </>
        )}
      </QueryBoundary>
      {reversing && (
        <ReasonDialog
          labels={
            reversing.source.type === 'HANDOVER'
              ? t.cashbook.reverse.handover
              : t.cashbook.reverse.otherIncome
          }
          onSubmit={(reason) =>
            reversing.source.type === 'HANDOVER'
              ? reverseHandover(reversing.source.id, { reason })
              : reverseOtherIncome(reversing.source.id, { reason })
          }
          onDone={() => {
            setReversing(undefined);
            void invalidateCashbook(queryClient);
          }}
          onClose={() => setReversing(undefined)}
        />
      )}
    </section>
  );
}
