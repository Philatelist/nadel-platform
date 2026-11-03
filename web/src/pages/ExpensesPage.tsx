import { useListExpenseCategories, useListExpenses, type ExpenseSummary } from '@nadel/api-client';
import type { MoneyAccountKind } from '@nadel/api-client';
import { formatDay } from '@nadel/shared';
import { keepPreviousData } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { categoryLabel, CategoryName } from '../components/cashbook/CategoryName';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import {
  Badge,
  buttonClass,
  DataTable,
  Money,
  PageHeader,
  Select,
  TextField,
  type Column,
} from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 50;

/** The expenses of the cash book for a period, by account and category. */
export function ExpensesPage() {
  const t = useT();
  const { locale } = useLocale();
  const l = t.expenses.list;
  useDocumentTitle(t.expenses.title);
  const canEnter = useCan('CASHBOOK_ENTER');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [account, setAccount] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [page, setPage] = useState(0);
  const categories = useListExpenseCategories({ kind: 'EXPENSE', includeArchived: true });
  const query = useListExpenses(
    {
      from: from || undefined,
      to: to || undefined,
      account: (account || undefined) as MoneyAccountKind | undefined,
      categoryId: categoryId || undefined,
      page,
      size: PAGE_SIZE,
    },
    { query: { placeholderData: keepPreviousData } },
  );

  const reset =
    <T,>(set: (value: T) => void) =>
    (value: T) => {
      set(value);
      setPage(0);
    };

  const columns: Column<ExpenseSummary>[] = [
    {
      key: 'date',
      header: l.columns.date,
      cell: (row) => (
        <span className="flex flex-wrap items-center gap-2">
          <Link to={`/cashbook/expenses/${row.id}`} className="text-accent underline">
            {formatDay(row.date, locale)}
          </Link>
          {row.reversed && <Badge tone="neutral">{l.reversed}</Badge>}
        </span>
      ),
    },
    { key: 'account', header: l.columns.account, cell: (row) => t.cashbook.account[row.account] },
    { key: 'category', header: l.columns.category, cell: (row) => <CategoryName category={row.category} /> },
    {
      key: 'counterparty',
      header: l.columns.counterparty,
      cell: (row) => (row.counterparty !== undefined ? <UserContent>{row.counterparty}</UserContent> : null),
    },
    { key: 'amount', header: l.columns.amount, numeric: true, cell: (row) => <Money amount={row.amount} /> },
    { key: 'files', header: l.columns.files, numeric: true, cell: (row) => row.attachmentCount },
  ];

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.expenses.title}
        actions={
          <>
            <Link to="/cashbook" className={buttonClass('secondary')}>
              {t.expenses.back}
            </Link>
            {canEnter && (
              <Link to="/cashbook/expenses/new" className={buttonClass('primary')}>
                {t.expenses.add}
              </Link>
            )}
          </>
        }
      />
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <TextField label={l.from} type="date" value={from} onChange={(e) => reset(setFrom)(e.target.value)} />
        <TextField label={l.to} type="date" value={to} onChange={(e) => reset(setTo)(e.target.value)} />
        <Select label={l.account} value={account} onChange={(e) => reset(setAccount)(e.target.value)}>
          <option value="">{l.all}</option>
          <option value="CASH">{t.cashbook.account.CASH}</option>
          <option value="BANK">{t.cashbook.account.BANK}</option>
        </Select>
        <Select label={l.category} value={categoryId} onChange={(e) => reset(setCategoryId)(e.target.value)}>
          <option value="">{l.all}</option>
          {(categories.data ?? []).map((category) => (
            <option key={category.id} value={category.id}>
              {categoryLabel(t, category)}
            </option>
          ))}
        </Select>
      </div>
      <QueryBoundary query={query}>
        {(data) => (
          <>
            <DataTable
              columns={columns}
              rows={data.items}
              rowKey={(row) => row.id}
              caption={t.expenses.title}
              emptyMessage={l.empty}
            />
            <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
          </>
        )}
      </QueryBoundary>
    </div>
  );
}
