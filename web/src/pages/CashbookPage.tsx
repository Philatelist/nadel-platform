import { useListMoneyAccounts } from '@nadel/api-client';
import { MoneyAccountCard } from '../components/cashbook/MoneyAccountCard';
import { QueryBoundary } from '../components/QueryBoundary';
import { useState } from 'react';
import { Link } from 'react-router';
import { HandoverDialog } from '../components/cashbook/HandoverDialog';
import { OtherIncomeDialog } from '../components/cashbook/OtherIncomeDialog';
import { Button, buttonClass, PageHeader } from '../components/ui';
import { useT } from '../i18n';
import { useCan } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';

/** Cash and bank: the two money accounts with their balances and journals. */
export function CashbookPage() {
  const t = useT();
  useDocumentTitle(t.cashbook.title);
  const canEnter = useCan('CASHBOOK_ENTER');
  const query = useListMoneyAccounts();
  const [dialog, setDialog] = useState<'handover' | 'otherIncome'>();

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
              <>
                <Link to="/cashbook/counts/new" className={buttonClass('secondary')}>
                  {t.cashbook.count.action}
                </Link>
                <Button variant="secondary" onClick={() => setDialog('handover')}>
                  {t.cashbook.handover.action}
                </Button>
                <Button variant="secondary" onClick={() => setDialog('otherIncome')}>
                  {t.cashbook.otherIncome.action}
                </Button>
              </>
            )}
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
      {dialog === 'handover' && <HandoverDialog onClose={() => setDialog(undefined)} />}
      {dialog === 'otherIncome' && <OtherIncomeDialog onClose={() => setDialog(undefined)} />}
    </div>
  );
}
