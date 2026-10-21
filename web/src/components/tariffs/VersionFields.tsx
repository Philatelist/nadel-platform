import { Basis, Frequency, type TariffVersionCreate } from '@nadel/api-client';
import { normalizeRateInput } from '@nadel/shared';
import { kyivToday } from '../../kyivToday';
import { useT } from '../../i18n';
import { problemCode, problemFieldErrors, problemMessage, type FieldErrors } from '../../problem';
import type { Messages } from '@nadel/shared';
import { Notice, Select, TextField } from '../ui';

/** What the user has typed for one tariff version; the rate stays a string, as typed. */
export interface VersionValues {
  validFrom: string;
  basis: Basis;
  frequency: Frequency;
  rate: string;
  dueDate: string;
  note: string;
}

export const emptyVersionValues: VersionValues = {
  validFrom: '',
  basis: Basis.PER_PLOT,
  frequency: Frequency.MONTHLY,
  rate: '',
  dueDate: '',
  note: '',
};

const BASES = Object.values(Basis);
const REPEATING_FREQUENCIES = Object.values(Frequency).filter((f) => f !== Frequency.ONE_OFF);

/**
 * Checks the typed values with the server's messages and builds the request body (the rate as the normalised string).
 * Returns the body when everything is valid, otherwise the errors by field.
 */
export function validateVersion(
  values: VersionValues,
  t: Messages,
): { data: TariffVersionCreate; errors?: undefined } | { data?: undefined; errors: FieldErrors } {
  const errors: Record<string, string> = {};
  const rate = normalizeRateInput(values.rate);
  if (values.validFrom === '') errors.validFrom = t.validation.REQUIRED;
  if (rate === undefined) errors.rate = t.validation.RATE_INVALID;
  const fixed = values.basis === Basis.FIXED_ONE_TIME;
  if (fixed && values.dueDate === '') errors.dueDate = t.validation.REQUIRED_dueDate;
  if (rate === undefined || Object.keys(errors).length > 0) return { errors };
  const note = values.note.trim();
  return {
    data: {
      validFrom: values.validFrom,
      basis: values.basis,
      frequency: fixed ? Frequency.ONE_OFF : values.frequency,
      rate,
      dueDate: fixed ? values.dueDate : undefined,
      note: note === '' ? undefined : note,
    },
  };
}

/** Maps a failed save to field errors and a form-level message (a taken date belongs to the date field). */
export function versionSaveErrors(t: Messages, error: unknown): { fields: FieldErrors; form?: string } {
  if (problemCode(error) === 'TARIFF_VERSION_DATE_TAKEN') {
    return { fields: { validFrom: t.problem.TARIFF_VERSION_DATE_TAKEN } };
  }
  const fields = problemFieldErrors(t, error);
  return Object.keys(fields).length > 0 ? { fields } : { fields: {}, form: problemMessage(t, error) };
}

/** The inputs of one tariff version; the parent owns the values and the submit. */
export function VersionFields({
  values,
  onChange,
  errors = {},
}: {
  values: VersionValues;
  onChange: (values: VersionValues) => void;
  errors?: FieldErrors;
}) {
  const t = useT();
  const v = t.tariffs.version;
  const fixed = values.basis === Basis.FIXED_ONE_TIME;
  const pastDated = values.validFrom !== '' && values.validFrom < kyivToday();

  function changeBasis(basis: Basis) {
    const frequency =
      basis === Basis.FIXED_ONE_TIME
        ? Frequency.ONE_OFF
        : values.frequency === Frequency.ONE_OFF
          ? Frequency.MONTHLY
          : values.frequency;
    onChange({ ...values, basis, frequency });
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label={v.validFrom}
          type="date"
          required
          value={values.validFrom}
          error={errors.validFrom}
          onChange={(e) => onChange({ ...values, validFrom: e.target.value })}
        />
        <TextField
          label={v.rate}
          hint={v.rateHint}
          inputMode="decimal"
          autoComplete="off"
          required
          value={values.rate}
          error={errors.rate}
          onChange={(e) => onChange({ ...values, rate: e.target.value })}
        />
        <Select
          label={v.basis}
          value={values.basis}
          error={errors.basis}
          onChange={(e) => changeBasis(e.target.value as Basis)}
        >
          {BASES.map((basis) => (
            <option key={basis} value={basis}>
              {t.tariffs.basis[basis]}
            </option>
          ))}
        </Select>
        <Select
          label={v.frequency}
          value={fixed ? Frequency.ONE_OFF : values.frequency}
          disabled={fixed}
          error={errors.frequency}
          onChange={(e) => onChange({ ...values, frequency: e.target.value as Frequency })}
        >
          {(fixed ? [Frequency.ONE_OFF] : REPEATING_FREQUENCIES).map((frequency) => (
            <option key={frequency} value={frequency}>
              {t.tariffs.frequency[frequency]}
            </option>
          ))}
        </Select>
        {fixed && (
          <TextField
            label={v.dueDate}
            type="date"
            required
            value={values.dueDate}
            error={errors.dueDate}
            onChange={(e) => onChange({ ...values, dueDate: e.target.value })}
          />
        )}
      </div>
      <TextField
        label={v.note}
        maxLength={500}
        value={values.note}
        error={errors.note}
        onChange={(e) => onChange({ ...values, note: e.target.value })}
      />
      {pastDated && <Notice tone="warning">{v.pastDated}</Notice>}
    </div>
  );
}
