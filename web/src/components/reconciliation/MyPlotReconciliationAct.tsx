import { useState } from 'react';
import { useT } from '../../i18n';
import { Button } from '../ui';
import { ReconciliationActForm } from './ReconciliationActForm';

/** «Акт звірки» on an own plot of the account page: opens the same form inline; the server checks ownership. */
export function MyPlotReconciliationAct({ plotId }: { plotId: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <div className="flex flex-col gap-3">
      <div>
        <Button variant="secondary" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
          {open ? t.reconciliationAct.close : t.reconciliationAct.open}
        </Button>
      </div>
      {open && <ReconciliationActForm plotId={plotId} />}
    </div>
  );
}
