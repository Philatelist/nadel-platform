import { formatDay, formatDayMonth, format } from './format';
import type { Messages } from './i18n';
import { formatMeterValue } from './meter';
import { formatMoney, formatRateWithCurrency } from './money';

/** The dates of a charge that decide whether its service period is shown next to the billing month. */
export interface ChargePeriod {
  /** The billing month the charge is booked in, "YYYY-MM". */
  billingMonth: string;
  serviceFrom: string;
  serviceTo: string;
}

/**
 * "за 23.09.2026–04.10.2026, нараховано у жовтні 2026": the service period and the billing month, but only when
 * the service period reaches outside the billing month; otherwise undefined (the month alone says it all).
 * Compares the texts of the dates, no date arithmetic.
 */
export function chargePeriodNote(charge: ChargePeriod, t: Messages, locale: string): string | undefined {
  const inMonth = (day: string): boolean => day.slice(0, 7) === charge.billingMonth;
  if (inMonth(charge.serviceFrom) && inMonth(charge.serviceTo)) return undefined;
  const [year = '', month = ''] = charge.billingMonth.split('-');
  const monthNames: Readonly<Record<string, string | undefined>> = t.charges.monthIn;
  const period =
    charge.serviceFrom === charge.serviceTo
      ? formatDay(charge.serviceFrom, locale)
      : `${formatDay(charge.serviceFrom, locale)}–${formatDay(charge.serviceTo, locale)}`;
  return format(t.charges.periodNote, { period, month: `${monthNames[month] ?? month} ${year}` });
}

/** One part of a charge calculation as the server sends it; every number is a string. */
export interface CalculationPartText {
  quantity?: string;
  unit?: 'PLOT' | 'SOTKA' | 'KWH' | 'M3';
  rate?: string;
  days: number;
  from: string;
  to: string;
}

/**
 * One calculation line composed from server strings only: "93,3 кВт·год × 4,32 ₴ = 403,06 ₴, 23.09–04.10 (12 днів)".
 * The "= amount" part is given only when the charge has a single part (the server states one amount per charge).
 * Quantity and rate are formatted, never multiplied; a part without a quantity shows the rate alone.
 */
export function calculationLine(
  part: CalculationPartText,
  amount: string | undefined,
  t: Messages,
  locale: string,
): string {
  const l = t.charges.line;
  const rate = part.rate === undefined ? undefined : formatRateWithCurrency(part.rate, locale);
  let line = '';
  if (rate !== undefined) {
    line =
      part.quantity !== undefined && part.unit !== undefined && part.unit !== 'PLOT'
        ? format(l.times, {
            quantity: formatMeterValue(part.quantity, locale),
            unit: l.unit[part.unit],
            rate,
          })
        : format(l.rateOnly, { rate });
  }
  if (amount !== undefined) {
    line =
      line === ''
        ? formatMoney(amount, locale)
        : format(l.equals, { line, amount: formatMoney(amount, locale) });
  }
  const days = format(pluralForm(l.days, new Intl.PluralRules(locale).select(part.days)), {
    count: part.days,
  });
  return part.from === part.to
    ? format(l.sameDay, { line, day: formatDayMonth(part.from, locale), days })
    : format(l.period, {
        line,
        from: formatDayMonth(part.from, locale),
        to: formatDayMonth(part.to, locale),
        days,
      });
}

function pluralForm(
  forms: Readonly<{ one: string; few: string; many: string; other: string }>,
  category: Intl.LDMLPluralRule,
): string {
  if (category === 'one') return forms.one;
  if (category === 'few') return forms.few;
  if (category === 'many') return forms.many;
  return forms.other;
}
