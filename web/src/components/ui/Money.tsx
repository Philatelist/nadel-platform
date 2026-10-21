import { formatMoney } from '@nadel/shared';
import { useLocale } from '../../i18n';

export type MoneyTone = 'default' | 'danger' | 'auto';

/** Displays a server-provided amount string; never parses it to a number. */
export function Money({ amount, tone = 'default' }: { amount: string; tone?: MoneyTone }) {
  const { locale } = useLocale();
  const danger = tone === 'danger' || (tone === 'auto' && amount.startsWith('-'));
  return (
    <span className={`font-mono ${danger ? 'text-danger' : 'text-ink'}`}>{formatMoney(amount, locale)}</span>
  );
}
