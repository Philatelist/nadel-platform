import { getListMoneyAccountsQueryKey, setOpeningBalance, type MoneyAccount } from '@nadel/api-client';
import { format, formatDateTime, formatDay, formatSignedMoney, normalizeMoneyInput } from '@nadel/shared';
import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { useCan } from '../../me';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import { Button, Money, Notice, Panel, TextField } from '../ui';
import { MovementsTable } from './MovementsTable';

const ACCOUNTS_KEY = getListMoneyAccountsQueryKey()[0];

/** The accounts list and every journal under it (balances and `beforeOpening` change with the opening balance). */
function invalidateAccounts(queryClient: QueryClient) {
  return queryClient.invalidateQueries({
    predicate: (query) => typeof query.queryKey[0] === 'string' && query.queryKey[0].startsWith(ACCOUNTS_KEY),
  });
}

/** The opening balance of an account, entered once; shown only to those who may approve. */
function OpeningBalanceForm({ account }: { account: MoneyAccount }) {
  const t = useT();
  const f = t.cashbook.openingForm;
  const queryClient = useQueryClient();
  const [amountText, setAmountText] = useState('');
  const [date, setDate] = useState(() => kyivToday());
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const amount = normalizeMoneyInput(amountText);
  const save = useMutation({
    mutationFn: (body: { amount: string; date: string }) => setOpeningBalance(account.id, body),
    onSuccess: () => invalidateAccounts(queryClient),
    onError: (error: unknown) => {
      const fields = problemFieldErrors(t, error);
      setServerErrors(fields);
      setNotice(Object.keys(fields).length > 0 ? undefined : problemMessage(t, error));
      void invalidateAccounts(queryClient);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (amount === undefined || date === '') return;
    save.mutate({ amount, date });
  }

  return (
    <form className="flex flex-col gap-3" onSubmit={submit} noValidate>
      <h3 className="text-lg font-semibold text-ink">{f.title}</h3>
      <p className="text-[15px] text-ink-muted">{f.text}</p>
      {notice && <Notice tone="danger">{notice}</Notice>}
      <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
        <TextField
          label={f.amount}
          inputMode="decimal"
          value={amountText}
          error={(submitted && amount === undefined ? f.amountInvalid : undefined) ?? serverErrors.amount}
          onChange={(e) => {
            setServerErrors({});
            setNotice(undefined);
            setAmountText(e.target.value);
          }}
        />
        <TextField
          label={f.date}
          type="date"
          value={date}
          max={kyivToday()}
          error={serverErrors.date}
          onChange={(e) => {
            setServerErrors({});
            setNotice(undefined);
            setDate(e.target.value);
          }}
        />
      </div>
      <div>
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? f.saving : f.save}
        </Button>
      </div>
    </form>
  );
}

/** One money account: its balance (or the prompt to enter the opening balance), warnings and journal. */
export function MoneyAccountCard({ account }: { account: MoneyAccount }) {
  const t = useT();
  const { locale } = useLocale();
  const canApprove = useCan('CASHBOOK_APPROVE');
  const name = t.cashbook.account[account.kind];

  return (
    <Panel title={name}>
      <div className="flex flex-col gap-4">
        {account.balance !== undefined ? (
          <div className="flex flex-col gap-1">
            <p className="text-[15px] text-ink-muted">
              {format(t.cashbook.balance, { date: formatDay(account.asOf, locale) })}
            </p>
            <p className="text-2xl font-semibold" data-testid={`balance-${account.kind}`}>
              <Money amount={account.balance} tone={account.negative ? 'danger' : 'default'} />
            </p>
            {account.opening !== undefined && (
              <p className="text-[15px] text-ink-muted">
                {format(t.cashbook.opening, {
                  amount: account.opening.amount,
                  date: formatDay(account.opening.date, locale),
                })}
              </p>
            )}
          </div>
        ) : (
          <p className="text-lg font-semibold text-ink">{t.cashbook.enterOpening}</p>
        )}
        {account.lastCount !== undefined && (
          <p className="text-[15px] text-ink-muted" data-testid={`last-count-${account.kind}`}>
            <Link to={`/cashbook/counts/${account.lastCount.id}`} className="text-accent underline">
              {format(t.cashbook.count.lastCount, {
                date: formatDateTime(account.lastCount.countedAt, locale),
              })}
            </Link>
            {': '}
            {account.lastCount.status === 'MATCHES'
              ? t.cashbook.count.lastCountMatches
              : format(t.cashbook.count.lastCountDifference, {
                  amount: formatSignedMoney(account.lastCount.difference, locale),
                })}
          </p>
        )}
        {account.unresolvedDifference !== undefined && account.lastCount !== undefined && (
          <Notice tone="warning">
            <Link to={`/cashbook/counts/${account.lastCount.id}`} className="underline">
              {format(t.cashbook.count.unresolved, {
                amount: formatSignedMoney(account.unresolvedDifference, locale),
              })}
            </Link>
          </Notice>
        )}
        {account.negative && <Notice tone="danger">{t.cashbook.negative[account.kind]}</Notice>}
        {account.opening === undefined && canApprove && <OpeningBalanceForm account={account} />}
        <MovementsTable accountId={account.id} />
      </div>
    </Panel>
  );
}
