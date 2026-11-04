import type { Expense } from '@nadel/api-client';
import { Link, useLocation, useNavigate } from 'react-router';
import { ExpenseForm } from '../components/cashbook/ExpenseForm';
import { buttonClass, PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

/** The reversed expense carried by the «reverse and record again» navigation, if any. */
function prefillOf(state: unknown): Expense | undefined {
  if (typeof state !== 'object' || state === null || !('prefill' in state)) return undefined;
  const { prefill } = state as { prefill: unknown };
  return typeof prefill === 'object' && prefill !== null && 'id' in prefill
    ? (prefill as Expense)
    : undefined;
}

/** A new expense, optionally prefilled from a reversed one. */
export function ExpenseFormPage() {
  const t = useT();
  useDocumentTitle(t.expenses.form.title);
  const navigate = useNavigate();
  const prefill = prefillOf(useLocation().state);
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.expenses.form.title}
        actions={
          <Link to="/cashbook" className={buttonClass('secondary')}>
            {t.expenses.back}
          </Link>
        }
      />
      <ExpenseForm prefill={prefill} onSaved={(expense) => navigate(`/cashbook/expenses/${expense.id}`)} />
    </div>
  );
}
