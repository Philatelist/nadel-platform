import {
  bookBankDifference,
  confirmBankReconciliation,
  enterBankStatement,
  reverseAccountMovement,
  useGetBankReconciliation,
  type AccountMovement,
  type BankFigures,
  type BankReconciliation,
} from '@nadel/api-client';
import {
  format,
  formatDateTime,
  formatDay,
  formatMonth,
  formatMoney,
  normalizeMoneyInput,
} from '@nadel/shared';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { invalidateCashbook } from '../components/cashbook/cashbookInvalidation';
import { STATUS_TONE } from '../components/cashbook/bankStatusTone';
import { cashbookProblemMessage } from '../components/cashbook/expenseErrors';
import { ReasonDialog } from '../components/cashbook/ReasonDialog';
import { Modal } from '../components/Modal';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import {
  Badge,
  Button,
  buttonClass,
  DataTable,
  Money,
  Notice,
  PageHeader,
  Panel,
  TextArea,
  TextField,
  type Column,
} from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { problemCode, problemFieldErrors, type FieldErrors } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

const FIGURES = ['opening', 'incoming', 'outgoing', 'closing'] as const;
type Figure = (typeof FIGURES)[number];

/** A typed signed amount as the contract's decimal string; text only, no arithmetic. Undefined when malformed. */
function normalizeSignedInput(text: string): string | undefined {
  const trimmed = text.trim().replace(/^[−–]/, '-');
  if (trimmed.startsWith('-')) {
    const rest = normalizeMoneyInput(trimmed.slice(1));
    return rest === undefined ? undefined : `-${rest}`;
  }
  return normalizeMoneyInput(trimmed);
}

function figureText(figures: BankFigures | undefined, figure: Figure): string {
  return figures?.[figure] ?? '';
}

/** The four typed figures as contract strings, or undefined when any is malformed. */
function parseFigures(texts: Record<Figure, string>): BankFigures | undefined {
  const opening = normalizeSignedInput(texts.opening);
  const incoming = normalizeMoneyInput(texts.incoming);
  const outgoing = normalizeMoneyInput(texts.outgoing);
  const closing = normalizeSignedInput(texts.closing);
  if (opening === undefined || incoming === undefined || outgoing === undefined || closing === undefined) {
    return undefined;
  }
  return { opening, incoming, outgoing, closing };
}

/** The four statement inputs next to the books and the server's differences. */
function StatementPanel({ month, data }: { month: string; data: BankReconciliation }) {
  const t = useT();
  const { locale } = useLocale();
  const b = t.cashbook.bank;
  const queryClient = useQueryClient();
  const canEnter = useCan('CASHBOOK_ENTER');
  const locked = data.status === 'RECONCILED';
  const [texts, setTexts] = useState<Record<Figure, string>>(() => ({
    opening: figureText(data.statement, 'opening'),
    incoming: figureText(data.statement, 'incoming'),
    outgoing: figureText(data.statement, 'outgoing'),
    closing: figureText(data.statement, 'closing'),
  }));
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();
  const [sent, setSent] = useState<BankFigures>();

  const parsed = parseFigures(texts);
  const readOnly = locked || !canEnter;
  // The server's difference strings only: any non-zero digit means the statement's flow differs from the books.
  const flowDiffers =
    data.differences !== undefined &&
    (/[1-9]/.test(data.differences.incoming) || /[1-9]/.test(data.differences.outgoing));

  function onError(error: unknown) {
    if (problemCode(error) === 'STATEMENT_INCONSISTENT' && sent !== undefined) {
      setServerErrors({});
      setNotice(
        format(b.inconsistent, {
          opening: formatMoney(sent.opening, locale),
          incoming: formatMoney(sent.incoming, locale),
          outgoing: formatMoney(sent.outgoing, locale),
          closing: formatMoney(sent.closing, locale),
        }),
      );
      return;
    }
    const fields = problemFieldErrors(t, error);
    setServerErrors(fields);
    setNotice(Object.keys(fields).length > 0 ? undefined : cashbookProblemMessage(t, locale, error));
    void invalidateCashbook(queryClient);
  }

  const save = useMutation({
    mutationFn: (body: BankFigures) => enterBankStatement(month, body),
    onSuccess: () => invalidateCashbook(queryClient),
    onError,
  });
  const confirm = useMutation({
    mutationFn: () => confirmBankReconciliation(month),
    onSuccess: () => invalidateCashbook(queryClient),
    onError,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (parsed === undefined || readOnly) return;
    setSent(parsed);
    setNotice(undefined);
    setServerErrors({});
    save.mutate(parsed);
  }

  function invalidText(figure: Figure): string | undefined {
    if (!submitted || parsed !== undefined) return undefined;
    const value = texts[figure];
    const ok =
      figure === 'incoming' || figure === 'outgoing'
        ? normalizeMoneyInput(value) !== undefined
        : normalizeSignedInput(value) !== undefined;
    if (ok) return undefined;
    return figure === 'incoming' || figure === 'outgoing' ? b.incomingInvalid : b.amountInvalid;
  }

  return (
    <Panel title={b.figuresTitle}>
      <form className="flex flex-col gap-4" onSubmit={submit} noValidate>
        {notice && <Notice tone="danger">{notice}</Notice>}
        {data.statement === undefined && <p className="text-ink-muted">{b.notEntered}</p>}
        <div className="overflow-x-auto">
          <table className="w-full text-left" aria-label={b.figuresTitle}>
            <thead>
              <tr className="text-ink-muted">
                <th className="py-1 pr-4 font-medium">{b.columns.statement}</th>
                <th className="py-1 pr-4 text-right font-medium">{b.columns.books}</th>
                <th className="py-1 text-right font-medium">{b.columns.difference}</th>
              </tr>
            </thead>
            <tbody>
              {FIGURES.map((figure) => (
                <tr key={figure} data-testid={`figure-${figure}`} className="align-top">
                  <td className="py-1 pr-4">
                    <TextField
                      label={b.figure[figure]}
                      inputMode="decimal"
                      value={texts[figure]}
                      disabled={readOnly}
                      error={invalidText(figure) ?? serverErrors[figure]}
                      onChange={(e) => {
                        setServerErrors({});
                        setNotice(undefined);
                        setTexts({ ...texts, [figure]: e.target.value });
                      }}
                    />
                  </td>
                  <td className="py-1 pr-4 text-right">
                    <Money amount={data.books[figure]} />
                  </td>
                  <td className="py-1 text-right">
                    {data.differences !== undefined && (
                      <Money amount={data.differences[figure]} signed tone="auto" />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data.statement !== undefined && data.differences !== undefined && flowDiffers && (
          <Notice tone="warning">{b.flowDiffers}</Notice>
        )}
        {locked && <p className="text-ink-muted">{b.lockedHint}</p>}
        {canEnter && !locked && (
          <div className="flex flex-wrap items-center gap-2">
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? b.saving : b.save}
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={!data.balanced || confirm.isPending}
              onClick={() => confirm.mutate()}
            >
              {confirm.isPending ? b.confirming : b.confirm}
            </Button>
            {!data.balanced && <span className="text-sm text-ink-muted">{b.confirmHint}</span>}
          </div>
        )}
      </form>
    </Panel>
  );
}

/** «Банківська різниця»: a signed amount and a reason, booked in the bank journal. */
function BankDifferenceDialog({ month, onClose }: { month: string; onClose: () => void }) {
  const t = useT();
  const { locale } = useLocale();
  const d = t.cashbook.bank.bookDialog;
  const queryClient = useQueryClient();
  const [amountText, setAmountText] = useState('');
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();

  const amount = normalizeSignedInput(amountText);
  const trimmed = reason.trim();
  const run = useMutation({
    mutationFn: (body: { amount: string; reason: string }) => bookBankDifference(month, body),
    onSuccess: () => {
      void invalidateCashbook(queryClient);
      onClose();
    },
    onError: (error: unknown) => {
      const fields = problemFieldErrors(t, error);
      setServerErrors(fields);
      setNotice(Object.keys(fields).length > 0 ? undefined : cashbookProblemMessage(t, locale, error));
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (amount === undefined || trimmed === '') return;
    run.mutate({ amount, reason: trimmed });
  }

  return (
    <Modal title={d.title} onClose={onClose}>
      <form className="mt-3 flex flex-col gap-4" onSubmit={submit} noValidate>
        <p className="text-[15px] text-ink-muted">{d.text}</p>
        {notice && <Notice tone="danger">{notice}</Notice>}
        <TextField
          label={d.amount}
          inputMode="decimal"
          value={amountText}
          error={(submitted && amount === undefined ? d.amountInvalid : undefined) ?? serverErrors.amount}
          onChange={(e) => {
            setServerErrors({});
            setAmountText(e.target.value);
          }}
        />
        <TextArea
          label={d.reason}
          value={reason}
          rows={3}
          maxLength={500}
          hint={d.hint}
          error={(submitted && trimmed === '' ? d.required : undefined) ?? serverErrors.reason}
          onChange={(e) => {
            setServerErrors({});
            setReason(e.target.value);
          }}
        />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            {d.cancel}
          </Button>
          <Button type="submit" disabled={run.isPending}>
            {run.isPending ? d.saving : d.submit}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** The month's bank journal lines as the server lists them, with the reversal of a booked bank difference. */
function MonthMovements({ data }: { data: BankReconciliation }) {
  const t = useT();
  const { locale } = useLocale();
  const b = t.cashbook.bank;
  const m = t.cashbook.movements;
  const queryClient = useQueryClient();
  const canApprove = useCan('CASHBOOK_APPROVE');
  const [reversing, setReversing] = useState<AccountMovement>();
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
      cell: (row) => (row.reason !== undefined ? <UserContent>{row.reason}</UserContent> : null),
    },
    {
      key: 'amount',
      header: m.columns.amount,
      numeric: true,
      cell: (row) => <Money amount={row.amount} signed tone="auto" />,
    },
    {
      key: 'actions',
      header: m.columns.actions,
      cell: (row) =>
        canApprove &&
        row.kind === 'BANK_DIFFERENCE' &&
        row.reversesMovementId === undefined &&
        !row.reversed ? (
          <Button variant="secondary" onClick={() => setReversing(row)}>
            {t.cashbook.reverse.action}
          </Button>
        ) : null,
    },
  ];
  return (
    <Panel title={b.movementsTitle}>
      <DataTable
        columns={columns}
        rows={data.movements}
        rowKey={(row) => row.id}
        caption={b.movementsTitle}
        emptyMessage={b.movementsEmpty}
      />
      {data.movementsTotal > data.movements.length && (
        <p className="mt-2 text-ink-muted">
          {format(b.movementsMore, {
            shown: String(data.movements.length),
            total: String(data.movementsTotal),
          })}
        </p>
      )}
      {reversing && (
        <ReasonDialog
          labels={b.reverseDialog}
          onSubmit={(reason) => reverseAccountMovement(data.accountId, reversing.id, { reason })}
          onDone={() => {
            setReversing(undefined);
            void invalidateCashbook(queryClient);
          }}
          onClose={() => setReversing(undefined)}
        />
      )}
    </Panel>
  );
}

function ReconciliationCard({ month, data }: { month: string; data: BankReconciliation }) {
  const t = useT();
  const { locale } = useLocale();
  const b = t.cashbook.bank;
  const canApprove = useCan('CASHBOOK_APPROVE');
  const [booking, setBooking] = useState(false);
  const stamp = data.reconciledAt !== undefined ? formatDateTime(data.reconciledAt, locale) : undefined;

  return (
    <>
      <Panel>
        <div className="flex flex-col gap-2">
          <p className="flex flex-wrap items-center gap-2">
            <Badge tone={STATUS_TONE[data.status]}>{b.status[data.status]}</Badge>
            {data.status === 'RECONCILED' && stamp !== undefined && (
              <span className="text-ink-muted">
                {data.reconciledByName !== undefined
                  ? format(b.reconciledBy, { name: data.reconciledByName, date: stamp })
                  : format(b.reconciledAt, { date: stamp })}
              </span>
            )}
          </p>
          {data.status === 'NEEDS_RECHECK' && data.reopened !== undefined && (
            <Notice tone="warning">
              {format(b.reopened, {
                kind: b.reopenedKind[data.reopened.kind],
                date: formatDay(data.reopened.date, locale),
                amount: formatMoney(data.reopened.amount, locale),
                at: formatDateTime(data.reopened.at, locale),
              })}
            </Notice>
          )}
        </div>
      </Panel>
      <StatementPanel month={month} data={data} />
      {canApprove && data.status !== 'RECONCILED' && (
        <div>
          <Button
            variant="secondary"
            disabled={data.statement === undefined}
            onClick={() => setBooking(true)}
          >
            {b.book}
          </Button>
        </div>
      )}
      <MonthMovements data={data} />
      {booking && <BankDifferenceDialog month={month} onClose={() => setBooking(false)} />}
    </>
  );
}

/** `/cashbook/bank/:month`: the reconciliation of one calendar month with the bank statement. */
export function BankReconciliationPage() {
  const t = useT();
  const { locale } = useLocale();
  const { month = '' } = useParams();
  const valid = /^\d{4}-(0[1-9]|1[0-2])$/.test(month);
  const title = format(t.cashbook.bank.title, { month: valid ? formatMonth(month, locale) : month });
  useDocumentTitle(title);
  const query = useGetBankReconciliation(month, { query: { enabled: valid } });
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={title}
        actions={
          <Link to="/cashbook" className={buttonClass('secondary')}>
            {t.cashbook.bank.back}
          </Link>
        }
      />
      {valid ? (
        <QueryBoundary query={query}>
          {(data) => <ReconciliationCard month={month} data={data} />}
        </QueryBoundary>
      ) : (
        <Notice tone="danger">{t.cashbook.bank.noAccess}</Notice>
      )}
    </div>
  );
}
