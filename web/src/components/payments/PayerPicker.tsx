import { useListPlotOwnerships } from '@nadel/api-client';
import { useT } from '../../i18n';
import { MemberPicker, type PickedMember } from '../ownership/MemberPicker';
import { UserContent } from '../UserContent';
import { Button } from '../ui';

/** The payer: optional, any member; the owners of the plot are offered first. */
export function PayerPicker({
  plotId,
  value,
  error,
  onChange,
}: {
  plotId: string | undefined;
  value: PickedMember | null;
  error?: string;
  onChange: (member: PickedMember | null) => void;
}) {
  const t = useT();
  const owners = useListPlotOwnerships(plotId ?? '', { query: { enabled: plotId !== undefined } });
  const current = (owners.data?.current ?? []).filter((o) => !o.memberArchived);
  return (
    <div className="flex flex-col gap-2">
      {value === null && current.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="text-sm font-medium text-ink-muted">{t.payments.form.payerOwners}</span>
          <div className="flex flex-wrap gap-2">
            {current.map((owner) => (
              <Button
                key={owner.id}
                variant="secondary"
                onClick={() => onChange({ id: owner.memberId, name: owner.memberName })}
              >
                <UserContent>{owner.memberName}</UserContent>
              </Button>
            ))}
          </div>
        </div>
      )}
      <MemberPicker
        label={t.payments.form.payer}
        value={value}
        excludeIds={[]}
        error={error}
        onChange={onChange}
      />
      {value === null && <p className="text-sm text-ink-muted">{t.payments.form.payerNone}</p>}
    </div>
  );
}
