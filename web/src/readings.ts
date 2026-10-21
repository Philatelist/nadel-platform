import type { MeterResource, ReadingRowError } from '@nadel/api-client';
import { format, formatDay, formatMeterValue, type Messages } from '@nadel/shared';
import { resourceUnit } from './meters';

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;
const METER_VALUE = /^\d+(\.\d{1,3})?$/;

function day(raw: string | undefined, locale: string): string | undefined {
  return raw !== undefined && ISO_DAY.test(raw) ? formatDay(raw, locale) : undefined;
}

function value(raw: string | undefined, locale: string): string | undefined {
  return raw !== undefined && METER_VALUE.test(raw) ? formatMeterValue(raw, locale) : undefined;
}

/**
 * The text of a refused row: the catalog wording of its code, followed by the facts the server sent (dates in the
 * locale format, values through `formatMeterValue`). Server text is never shown; an unknown code reads as UNKNOWN.
 */
export function readingErrorMessage(
  t: Messages,
  locale: string,
  resource: MeterResource,
  error: Pick<ReadingRowError, 'code' | 'params'>,
): string {
  const r = t.readings;
  const known: Readonly<Record<string, string>> = r.errors;
  const base = known[error.code] ?? t.problem.UNKNOWN;
  const p = error.params ?? {};
  const unit = resourceUnit(t, resource);
  const details: string[] = [];
  switch (error.code) {
    case 'READING_DATE_IN_FUTURE': {
      const today = day(p.today, locale);
      if (today) details.push(format(r.details.today, { today }));
      break;
    }
    case 'READING_DATE_OUTSIDE_METER': {
      const installedOn = day(p.installedOn, locale);
      const removedOn = day(p.removedOn, locale);
      if (installedOn && removedOn) details.push(format(r.details.periodFromTo, { installedOn, removedOn }));
      else if (installedOn) details.push(format(r.details.periodFrom, { installedOn }));
      break;
    }
    case 'READING_OUT_OF_ORDER': {
      const previousValue = value(p.previousValue, locale);
      const previousDate = day(p.previousTakenOn, locale);
      if (previousValue && previousDate) {
        details.push(format(r.details.previous, { value: previousValue, unit, date: previousDate }));
      }
      const nextValue = value(p.nextValue, locale);
      const nextDate = day(p.nextTakenOn, locale);
      if (nextValue && nextDate) {
        details.push(format(r.details.next, { value: nextValue, unit, date: nextDate }));
      }
      break;
    }
    default:
      break;
  }
  return details.length > 0 ? `${base}. ${details.join(' ')}` : base;
}
