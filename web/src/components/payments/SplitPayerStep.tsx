import { useT } from '../../i18n';
import { MemberPicker, type PickedMember } from '../ownership/MemberPicker';

/** The first step of a split payment: the payer, whose current plots the server then lists with their debts. */
export function SplitPayerStep({
  payer,
  error,
  onChange,
}: {
  payer: PickedMember | null;
  error?: string;
  onChange: (member: PickedMember | null) => void;
}) {
  const t = useT();
  return (
    <div className="flex flex-col gap-2">
      <MemberPicker
        label={t.payments.split.payer}
        value={payer}
        excludeIds={[]}
        error={error}
        onChange={onChange}
      />
      <p className="text-sm text-ink-muted">{t.payments.split.payerHint}</p>
    </div>
  );
}
