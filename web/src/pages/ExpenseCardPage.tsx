import { reverseExpense, useGetExpense, type Expense } from '@nadel/api-client';
import { format, formatDateTime, formatDay } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { AttachmentList } from '../components/cashbook/AttachmentList';
import { CategoryName } from '../components/cashbook/CategoryName';
import { invalidateCashbook } from '../components/cashbook/cashbookInvalidation';
import { ReasonDialog } from '../components/cashbook/ReasonDialog';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import { Badge, buttonClass, Button, Money, PageHeader, Panel } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:gap-4">
      <dt className="text-ink-muted sm:w-48 sm:shrink-0">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </div>
  );
}

function ExpenseCard({ expense }: { expense: Expense }) {
  const t = useT();
  const { locale } = useLocale();
  const c = t.expenses.card;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canApprove = useCan('CASHBOOK_APPROVE');
  const [reversing, setReversing] = useState(false);

  const document =
    expense.documentNumber !== undefined && expense.documentDate !== undefined
      ? format(c.documentLine, {
          number: expense.documentNumber,
          date: formatDay(expense.documentDate, locale),
        })
      : expense.documentNumber !== undefined
        ? format(c.documentNumberOnly, { number: expense.documentNumber })
        : expense.documentDate !== undefined
          ? format(c.documentDateOnly, { date: formatDay(expense.documentDate, locale) })
          : undefined;

  return (
    <>
      <Panel>
        <dl className="flex flex-col gap-3">
          <Row label={c.account}>{t.cashbook.account[expense.account]}</Row>
          <Row label={c.date}>{formatDay(expense.date, locale)}</Row>
          <Row label={c.amount}>
            <Money amount={expense.amount} />
          </Row>
          <Row label={c.category}>
            <CategoryName category={expense.category} />
          </Row>
          {expense.counterparty !== undefined && (
            <Row label={c.counterparty}>
              <UserContent>{expense.counterparty}</UserContent>
            </Row>
          )}
          {expense.description !== undefined && (
            <Row label={c.description}>
              <UserContent>{expense.description}</UserContent>
            </Row>
          )}
          {document !== undefined && (
            <Row label={c.document}>
              {expense.documentNumber !== undefined && expense.documentDate === undefined ? (
                <UserContent>{document}</UserContent>
              ) : (
                document
              )}
            </Row>
          )}
          {expense.createdByName !== undefined && (
            <Row label={c.createdBy}>
              <UserContent>{expense.createdByName}</UserContent> · {formatDateTime(expense.createdAt, locale)}
            </Row>
          )}
          {expense.reversal !== undefined && (
            <Row label={c.reversed}>
              <span className="flex flex-col gap-0.5">
                <span>
                  {formatDay(expense.reversal.date, locale)}:{' '}
                  <UserContent>{expense.reversal.reason}</UserContent>
                </span>
                {expense.reversal.createdByName !== undefined && (
                  <span className="text-sm text-ink-muted">
                    {format(c.reversalBy, { name: expense.reversal.createdByName })}
                  </span>
                )}
              </span>
            </Row>
          )}
        </dl>
      </Panel>
      <Panel title={t.expenses.attachments.list}>
        <AttachmentList expenseId={expense.id} attachments={expense.attachments} />
      </Panel>
      {canApprove && !expense.reversed && (
        <div>
          <Button variant="danger" onClick={() => setReversing(true)}>
            {c.redo}
          </Button>
        </div>
      )}
      {reversing && (
        <ReasonDialog
          labels={t.expenses.reverse}
          onSubmit={(reason) => reverseExpense(expense.id, { reason })}
          onDone={() => {
            void invalidateCashbook(queryClient);
            navigate('/cashbook/expenses/new', { state: { prefill: expense } });
          }}
          onClose={() => setReversing(false)}
        />
      )}
    </>
  );
}

/** One expense with its receipt files; those who may approve can reverse it and record it again. */
export function ExpenseCardPage() {
  const t = useT();
  useDocumentTitle(t.expenses.card.title);
  const { id = '' } = useParams();
  const query = useGetExpense(id);
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.expenses.card.title}
        actions={
          <>
            {query.data?.reversed && <Badge tone="neutral">{t.expenses.card.reversed}</Badge>}
            <Link to="/cashbook/expenses" className={buttonClass('secondary')}>
              {t.expenses.toList}
            </Link>
          </>
        }
      />
      <QueryBoundary query={query}>{(expense) => <ExpenseCard expense={expense} />}</QueryBoundary>
    </div>
  );
}
