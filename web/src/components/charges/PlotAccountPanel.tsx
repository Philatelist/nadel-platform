import { ApiError, useGetPlotAccount, useGetSettings, type Charge } from '@nadel/api-client';
import { useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { useT } from '../../i18n';
import { useCan } from '../../me';
import { QueryBoundary } from '../QueryBoundary';
import { AllocatePrepaymentDialog } from '../payments/AllocatePrepaymentDialog';
import { Button, Notice, Panel, TextField } from '../ui';
import { isAdjustment } from './chargeLabels';
import { ChargeDetailDialog } from './ChargeDetailDialog';
import { ChargeList } from './ChargeList';
import { DebtByType } from './DebtByType';
import { OpeningBalanceForm } from './OpeningBalanceForm';
import { ReverseChargeDialog } from './ReverseChargeDialog';

/**
 * The account of one plot: debt per charge type, then the charges newest first with a date range the server applies.
 * Renders nothing when the server refuses the account. `readOnly` hides every action (the member's own account).
 */
export function PlotAccountPanel({
  plotId,
  title,
  readOnly = false,
}: {
  plotId: string;
  title?: ReactNode;
  readOnly?: boolean;
}) {
  const t = useT();
  const c = t.charges;
  const canReverse = useCan('CHARGES_REVERSE') && !readOnly;
  const canRun = useCan('CHARGES_RUN') && !readOnly;
  const canViewPayments = useCan('PAYMENTS_VIEW');
  const canEnterPayments = useCan('PAYMENTS_ENTER') && !readOnly;
  // GET /settings is open to every member of the cooperative. The button is offered only once the setting is known
  // to be off; with automatic allocation on, the server places the prepayment itself.
  const settings = useGetSettings({ query: { enabled: canEnterPayments } });
  const canAllocate = canEnterPayments && settings.data?.autoAllocatePrepayment === false;
  const [allocating, setAllocating] = useState(false);
  const [paymentsOnly, setPaymentsOnly] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [searchParams, setSearchParams] = useSearchParams();
  const adjustmentsOnly = searchParams.get('adjustments') === '1';
  const [chosen, setOpened] = useState<Charge>();
  const [dismissedParam, setDismissedParam] = useState<string>();
  const [reversing, setReversing] = useState<Charge>();
  const [enteringBalance, setEnteringBalance] = useState(false);
  const [done, setDone] = useState<string>();

  const rangeInvalid = from !== '' && to !== '' && from > to;
  const params = {
    ...(rangeInvalid ? {} : { ...(from ? { from } : {}), ...(to ? { to } : {}) }),
    ...(paymentsOnly ? { kind: 'PAYMENTS' as const } : {}),
  };
  const filtered = !rangeInvalid && (from !== '' || to !== '');
  const account = useGetPlotAccount(plotId, params);

  const setAdjustmentsOnly = (on: boolean) =>
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (on) next.set('adjustments', '1');
        else next.delete('adjustments');
        return next;
      },
      { replace: true },
    );

  // `?charge=<id>` (a link from a recalculation) opens that charge once the account is loaded; an unknown id is ignored.
  const chargeParam = searchParams.get('charge');
  const linked =
    chargeParam && chargeParam !== dismissedParam
      ? account.data?.charges.find((x) => x.id === chargeParam)
      : undefined;
  const opened = chosen ?? linked;

  if (account.error instanceof ApiError && (account.error.status === 404 || account.error.status === 403)) {
    return null;
  }

  return (
    <Panel title={title ?? c.panelTitle}>
      {done && <Notice tone="success">{done}</Notice>}
      <QueryBoundary query={account}>
        {(data) => {
          // The filter is applied to the loaded list so an adjustment still finds its original charge.
          const shown = adjustmentsOnly ? data.charges.filter(isAdjustment) : data.charges;
          const payments = adjustmentsOnly ? [] : data.payments;
          const placements = adjustmentsOnly ? [] : data.prepaymentAllocations;
          return (
            <>
              <DebtByType
                account={data}
                onAllocatePrepayment={canAllocate ? () => setAllocating(true) : undefined}
              />
              {canRun && (
                <div>
                  <Button
                    variant="secondary"
                    onClick={() => {
                      setDone(undefined);
                      setEnteringBalance(true);
                    }}
                  >
                    {c.opening.action}
                  </Button>
                </div>
              )}
              <div className="flex flex-wrap items-start gap-3">
                <TextField
                  label={c.filterFrom}
                  type="date"
                  value={from}
                  error={rangeInvalid ? c.filterInvalid : undefined}
                  onChange={(e) => setFrom(e.target.value)}
                />
                <TextField
                  label={c.filterTo}
                  type="date"
                  value={to}
                  onChange={(e) => setTo(e.target.value)}
                />
                <label className="flex min-h-11 items-center gap-2 self-end text-[15px]">
                  <input
                    type="checkbox"
                    checked={adjustmentsOnly}
                    onChange={(e) => setAdjustmentsOnly(e.target.checked)}
                  />
                  {c.adjustment.only}
                </label>
                <label className="flex min-h-11 items-center gap-2 self-end text-[15px]">
                  <input
                    type="checkbox"
                    checked={paymentsOnly}
                    onChange={(e) => setPaymentsOnly(e.target.checked)}
                  />
                  {c.paymentsOnly}
                </label>
                {(from !== '' || to !== '') && (
                  <Button
                    variant="secondary"
                    className="self-end"
                    onClick={() => {
                      setFrom('');
                      setTo('');
                    }}
                  >
                    {c.filterReset}
                  </Button>
                )}
              </div>
              {shown.length === 0 && payments.length === 0 && placements.length === 0 ? (
                <p className="text-ink-muted">
                  {paymentsOnly ? c.paymentsEmpty : filtered || adjustmentsOnly ? c.nothingFound : c.empty}
                </p>
              ) : (
                <ChargeList
                  charges={shown}
                  payments={payments}
                  placements={placements}
                  canOpenPayments={canViewPayments}
                  findCharge={(id) => data.charges.find((x) => x.id === id)}
                  canReverse={canReverse}
                  onOpen={setOpened}
                  onReverse={(charge) => {
                    setDone(undefined);
                    setReversing(charge);
                  }}
                />
              )}
            </>
          );
        }}
      </QueryBoundary>
      {opened && (
        <ChargeDetailDialog
          charge={opened}
          charges={account.data?.charges}
          onOpenCharge={setOpened}
          onClose={() => {
            setOpened(undefined);
            setDismissedParam(chargeParam ?? undefined);
          }}
        />
      )}
      {reversing && (
        <ReverseChargeDialog
          charge={reversing}
          onClose={() => setReversing(undefined)}
          onDone={() => {
            setReversing(undefined);
            setDone(c.reverse.done);
          }}
        />
      )}
      {allocating && <AllocatePrepaymentDialog plotId={plotId} onDone={() => setAllocating(false)} />}
      {enteringBalance && (
        <OpeningBalanceForm
          plotId={plotId}
          onClose={() => setEnteringBalance(false)}
          onDone={() => {
            setEnteringBalance(false);
            setDone(c.opening.done);
          }}
        />
      )}
    </Panel>
  );
}
