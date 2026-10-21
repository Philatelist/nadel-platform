import { format, formatDay } from '@nadel/shared';
import { useLocale, useT } from '../../i18n';
import { kyivToday } from '../../kyivToday';
import { TextField } from '../ui';

/**
 * A reading's date: filled with today (Europe/Kyiv) unless a value is given, never later than today, with the quiet
 * hint that names today's date. `onChange` gets the typed day, or `undefined` when the field is cleared (the caller
 * then falls back to the default).
 */
export function ReadingDateField({
  label,
  value,
  onChange,
  min,
  error,
  showHint = true,
}: {
  label?: string;
  value?: string;
  onChange: (value: string | undefined) => void;
  min?: string;
  error?: string;
  showHint?: boolean;
}) {
  const t = useT();
  const { locale } = useLocale();
  const today = kyivToday();
  return (
    <TextField
      label={label ?? t.readings.dateLabel}
      type="date"
      value={value ?? today}
      max={today}
      min={min}
      error={error}
      hint={showHint ? format(t.readings.dateHint, { date: formatDay(today, locale) }) : undefined}
      onChange={(e) => onChange(e.target.value === '' ? undefined : e.target.value)}
    />
  );
}
