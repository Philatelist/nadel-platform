import { useListMoneyAccounts } from '@nadel/api-client';
import { MoneyAccountCard } from '../components/cashbook/MoneyAccountCard';
import { QueryBoundary } from '../components/QueryBoundary';
import { Link } from 'react-router';
import { buttonClass, PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

/** Cash and bank: the two money accounts with their balances and journals. */
export function CashbookPage() {
  const t = useT();
  useDocumentTitle(t.cashbook.title);
  const canEnter = useCan('CASHBOOK_ENTER');
  const query = useListMoneyAccounts();

  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={t.cashbook.title}
        actions={
          <>
            <Link to="/cashbook/expenses" className={buttonClass('secondary')}>
              {t.expenses.title}
            </Link>
            {canEnter && (
              <Link to="/cashbook/expenses/new" className={buttonClass('primary')}>
                {t.expenses.add}
              </Link>
            )}
          </>
        }
      />
      <QueryBoundary query={query}>
        {(accounts) => (
          <div className="flex flex-col gap-4">
            {accounts.map((account) => (
              <MoneyAccountCard key={account.id} account={account} />
            ))}
          </div>
        )}
      </QueryBoundary>
    </div>
  );
}
