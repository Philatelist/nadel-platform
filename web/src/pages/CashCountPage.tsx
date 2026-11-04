import {
  bookCashCountDifference,
  enterCashCount,
  getCashCountActPdf,
  useGetCashCount,
  type AccountMovement,
  type CashCount,
  type CashCountInput,
} from '@nadel/api-client';
import { format, formatDateTime, formatDay, normalizeMoneyInput } from '@nadel/shared';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { invalidateCashbook } from '../components/cashbook/cashbookInvalidation';
import { cashbookProblemMessage } from '../components/cashbook/expenseErrors';
import { ReasonDialog } from '../components/cashbook/ReasonDialog';
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
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { usePdfAction } from '../hooks/usePdfAction';
import { useLocale, useT } from '../i18n';
import { kyivLocalToInstant, kyivNowLocal } from '../kyivDateTime';
import { useCan } from '../me';
import { problemFieldErrors, problemMessage, type FieldErrors } from '../problem';
import { useDocumentTitle } from '../useDocumentTitle';

export const COUNT_PREVIEW_DEBOUNCE_MS = 400;

/** The server's difference is "0.00" exactly when the count matches; compared as text, never as a number. */
function isZero(amount: string): boolean {
  return /^-?0+(\.0+)?$/.test(amount);
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:gap-4">
      <dt className="text-ink-muted sm:w-56 sm:shrink-0">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </div>
  );
}

/** The cash lines since the previous count, as the server lists them. */
function MovementsSince({ count }: { count: CashCount }) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.cashbook.count;
  const m = t.cashbook.movements;
  const columns: Column<AccountMovement>[] = [
    { key: 'date', header: m.columns.date, cell: (row) => formatDay(row.date, locale) },
    { key: 'kind', header: m.columns.kind, cell: (row) => t.cashbook.kind[row.kind] },
    {
      key: 'reason',
      header: m.columns.details,
      cell: (row) => (row.reason !== undefined ? <UserContent>{row.reason}</UserContent> : null),
    },
    {
      key: 'amount',
      header: m.columns.amount,
      numeric: true,
      cell: (row) => <Money amount={row.amount} signed tone="auto" />,
    },
  ];
  return (
    <Panel title={c.sinceTitle}>
      {count.previousCount !== undefined && (
        <p className="mb-2 text-ink-muted">
          {format(c.previous, { date: formatDateTime(count.previousCount.countedAt, locale) })}
        </p>
      )}
      <DataTable
        columns={columns}
        rows={count.movementsSince}
        rowKey={(row) => row.id}
        caption={c.sinceTitle}
        emptyMessage={c.sinceEmpty}
      />
      {count.movementsSinceTotal > count.movementsSince.length && (
        <p className="mt-2 text-ink-muted">
          {format(c.sinceMore, {
            shown: String(count.movementsSince.length),
            total: String(count.movementsSinceTotal),
          })}
        </p>
      )}
    </Panel>
  );
}

/** The form of a new count; the server previews the book balance and the difference as the values are typed. */
function CashCountForm() {
  const t = useT();
  const c = t.cashbook.count;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [countedAt, setCountedAt] = useState(() => kyivNowLocal());
  const [countedText, setCountedText] = useState('');
  const [explanation, setExplanation] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string>();
  const { locale } = useLocale();

  const counted = normalizeMoneyInput(countedText);
  const instant = kyivLocalToInstant(countedAt);
  const request: CashCountInput | undefined =
    counted !== undefined && instant !== undefined ? { countedAt: instant, counted } : undefined;
  const key = request === undefined ? '' : JSON.stringify(request);
  const settledKey = useDebouncedValue(key, COUNT_PREVIEW_DEBOUNCE_MS);

  const preview = useQuery({
    queryKey: ['cash-count-preview', settledKey],
    queryFn: () => enterCashCount(JSON.parse(settledKey) as CashCountInput, { preview: true }),
    enabled: settledKey !== '',
    placeholderData: keepPreviousData,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });

  const waiting = key !== settledKey || preview.isFetching;
  const fresh = key !== '' && !waiting && !preview.isError && !preview.isPlaceholderData;
  const current = fresh ? preview.data : undefined;
  const shown = key === '' ? undefined : preview.data;
  const previewError = key !== '' && !waiting ? preview.error : undefined;
  const needsExplanation = current !== undefined && !isZero(current.difference);
  const explanationMissing = needsExplanation && explanation.trim() === '';

  const save = useMutation({
    mutationFn: (body: CashCountInput) => enterCashCount(body),
    onSuccess: (saved) => {
      void invalidateCashbook(queryClient);
      if (saved.id !== undefined) navigate(`/cashbook/counts/${saved.id}`);
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
    if (request === undefined || current === undefined || explanationMissing) return;
    const text = explanation.trim();
    save.mutate({ ...request, ...(text !== '' ? { explanation: text } : {}) });
  }

  function touch() {
    setServerErrors({});
    setNotice(undefined);
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit} noValidate>
      <Panel>
        <div className="flex flex-col gap-4">
          {notice && <Notice tone="danger">{notice}</Notice>}
          <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
            <TextField
              label={c.countedAt}
              type="datetime-local"
              value={countedAt}
              max={kyivNowLocal()}
              error={serverErrors.countedAt}
              onChange={(e) => {
                touch();
                setCountedAt(e.target.value);
              }}
            />
            <TextField
              label={c.counted}
              inputMode="decimal"
              value={countedText}
              error={
                (submitted && counted === undefined ? c.countedInvalid : undefined) ?? serverErrors.counted
              }
              onChange={(e) => {
                touch();
                setCountedText(e.target.value);
              }}
            />
          </div>
          {previewError !== undefined && (
            <Notice tone="danger">{cashbookProblemMessage(t, locale, previewError)}</Notice>
          )}
          {shown !== undefined && (
            <dl className="flex flex-col gap-2" aria-busy={waiting} data-testid="count-preview">
              <Row label={c.bookBalance}>
                <Money amount={shown.bookBalance} />
              </Row>
              <Row label={c.difference}>
                <Money amount={shown.difference} signed tone="auto" />
              </Row>
            </dl>
          )}
          {waiting && key !== '' && <p className="text-ink-muted">{c.checking}</p>}
          {needsExplanation && (
            <TextArea
              label={c.explanation}
              value={explanation}
              rows={3}
              maxLength={500}
              hint={c.explanationHint}
              error={
                (submitted && explanationMissing ? c.explanationRequired : undefined) ??
                serverErrors.explanation
              }
              onChange={(e) => {
                touch();
                setExplanation(e.target.value);
              }}
            />
          )}
          <div className="flex gap-2">
            <Button type="submit" disabled={save.isPending || current === undefined}>
              {save.isPending ? c.saving : c.submit}
            </Button>
          </div>
        </div>
      </Panel>
      {shown !== undefined && <MovementsSince count={shown} />}
    </form>
  );
}

/** A saved count: its result, how its difference stands, the act and (for those who approve) the booking. */
function CashCountCard({ count }: { count: CashCount }) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.cashbook.count;
  const queryClient = useQueryClient();
  const canApprove = useCan('CASHBOOK_APPROVE');
  const act = usePdfAction();
  const [booking, setBooking] = useState(false);
  const countId = count.id ?? '';

  const bookedLine =
    count.booking !== undefined
      ? format(c.booked[count.booking.kind], {
          amount: count.booking.amount,
          date: formatDay(count.booking.date, locale),
        })
      : undefined;

  return (
    <>
      <Panel>
        <dl className="flex flex-col gap-3">
          <Row label={c.countedAt}>{formatDateTime(count.countedAt, locale)}</Row>
          <Row label={c.counted}>
            <Money amount={count.counted} />
          </Row>
          <Row label={c.bookBalance}>
            <Money amount={count.bookBalance} />
          </Row>
          <Row label={c.result}>
            {count.status === 'MATCHES' ? (
              c.matches
            ) : (
              <span className="flex flex-col gap-0.5">
                <span>
                  {c.difference}: <Money amount={count.difference} signed tone="auto" />
                </span>
                {count.explanation !== undefined && <UserContent>{count.explanation}</UserContent>}
              </span>
            )}
          </Row>
          {count.status !== 'MATCHES' && (
            <Row label={c.status}>
              {count.status === 'UNRESOLVED' ? (
                <Badge tone="warning">{format(c.unresolved, { amount: count.difference })}</Badge>
              ) : (
                <span className="flex flex-col gap-0.5">
                  <span>
                    {bookedLine}
                    {count.booking?.reversed && <> · {c.bookedReversed}</>}
                  </span>
                  {count.booking !== undefined && (
                    <span>
                      <UserContent>{count.booking.reason}</UserContent>
                    </span>
                  )}
                  {count.booking?.createdByName !== undefined && (
                    <span className="text-sm text-ink-muted">
                      {format(c.bookedBy, { name: count.booking.createdByName })}
                    </span>
                  )}
                </span>
              )}
            </Row>
          )}
          {count.createdByName !== undefined && (
            <Row label={c.createdBy}>
              <UserContent>{count.createdByName}</UserContent>
            </Row>
          )}
        </dl>
      </Panel>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="secondary"
          disabled={act.pending}
          onClick={() => void act.run(() => getCashCountActPdf(countId), `cash_count_${count.countedOn}.pdf`)}
        >
          {act.pending ? c.actPending : c.act}
        </Button>
        {canApprove && count.status === 'UNRESOLVED' && (
          <Button onClick={() => setBooking(true)}>{c.book}</Button>
        )}
      </div>
      {act.error !== undefined && <Notice tone="danger">{problemMessage(t, act.error)}</Notice>}
      <MovementsSince count={count} />
      {booking && (
        <ReasonDialog
          labels={c.bookDialog}
          onSubmit={(reason) => bookCashCountDifference(countId, { reason })}
          onDone={() => {
            setBooking(false);
            void invalidateCashbook(queryClient);
          }}
          onClose={() => setBooking(false)}
        />
      )}
    </>
  );
}

/** `/cashbook/counts/new`: a new cash count. */
export function CashCountNewPage() {
  const t = useT();
  useDocumentTitle(t.cashbook.count.newTitle);
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.cashbook.count.newTitle}
        actions={
          <Link to="/cashbook" className={buttonClass('secondary')}>
            {t.cashbook.count.back}
          </Link>
        }
      />
      <CashCountForm />
    </div>
  );
}

/** `/cashbook/counts/:id`: one saved cash count. */
export function CashCountPage() {
  const t = useT();
  useDocumentTitle(t.cashbook.count.cardTitle);
  const { id = '' } = useParams();
  const query = useGetCashCount(id);
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.cashbook.count.cardTitle}
        actions={
          <Link to="/cashbook" className={buttonClass('secondary')}>
            {t.cashbook.count.back}
          </Link>
        }
      />
      <QueryBoundary query={query}>{(count) => <CashCountCard count={count} />}</QueryBoundary>
    </div>
  );
}
