import { formatMoney, formatSignedMoney } from '@nadel/shared';
import { useLocale } from '../../i18n';

export type MoneyTone = 'default' | 'danger' | 'auto';

/** Displays a server-provided amount string; never parses it to a number. */
export function Money({
  amount,
  tone = 'default',
  signed = false,
}: {
  amount: string;
  tone?: MoneyTone;
  signed?: boolean;
}) {
  const { locale } = useLocale();
  const danger = tone === 'danger' || (tone === 'auto' && amount.startsWith('-'));
  return (
    <span className={`font-mono ${danger ? 'text-danger' : 'text-ink'}`}>
      {signed ? formatSignedMoney(amount, locale) : formatMoney(amount, locale)}
    </span>
  );
}
