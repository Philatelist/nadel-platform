import type { Charge } from '@nadel/api-client';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { UserContent } from '../UserContent';
import { adjustmentLead } from './chargeLabels';

const linkClass = 'text-accent underline';

/**
 * "Adjustment for <month>: −5,00 ₴ — <reason>" with a link to the original charge (when it is in the loaded list)
 * and to the recalculation (only when the server sent its id, that is to holders of CHARGES_VIEW).
 */
export function AdjustmentLine({
  charge,
  original,
  onOpenOriginal,
}: {
  charge: Charge;
  original?: Charge;
  onOpenOriginal?: (original: Charge) => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const a = t.charges.adjustment;
  return (
    <div className="flex flex-col gap-0.5 text-sm">
      <div>
        {adjustmentLead(t, charge, locale)}
        {charge.reason && (
          <>
            {' — '}
            <UserContent>{charge.reason}</UserContent>
          </>
        )}
      </div>
      <div className="flex flex-wrap gap-x-4">
        {original && onOpenOriginal && (
          <button type="button" className={linkClass} onClick={() => onOpenOriginal(original)}>
            {a.openOriginal}
          </button>
        )}
        {charge.recalculationId && (
          <Link to={`/charges/recalculations/${charge.recalculationId}`} className={linkClass}>
            {a.openRecalculation}
          </Link>
        )}
      </div>
    </div>
  );
}
