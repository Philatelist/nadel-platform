import { Basis, Frequency } from '@nadel/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { messages } from '@nadel/shared';
import { kyivToday } from '../../kyivToday';
import { renderWithApp } from '../../test/render';
import { emptyVersionValues, validateVersion, VersionFields, type VersionValues } from './VersionFields';

let latest: VersionValues = emptyVersionValues;

function Harness({ initial = emptyVersionValues }: { initial?: VersionValues }) {
  const [values, setValues] = useState(initial);
  return (
    <VersionFields
      values={values}
      onChange={(next) => {
        latest = next;
        setValues(next);
      }}
    />
  );
}

const WARNING = 'Charges for past periods will be recalculated';

function addDays(day: string, days: number): string {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

describe('VersionFields', () => {
  it('forces a one-off frequency and requires the due date for a fixed one-time amount', async () => {
    const user = userEvent.setup();
    renderWithApp(<Harness />);
    expect(screen.queryByLabelText('Due date')).toBeNull();
    expect(screen.queryByRole('option', { name: 'One-off' })).toBeNull();

    await user.selectOptions(screen.getByLabelText('Basis'), Basis.FIXED_ONE_TIME);

    const frequency = screen.getByLabelText('Frequency');
    expect(frequency).toBeDisabled();
    expect(frequency).toHaveValue(Frequency.ONE_OFF);
    expect(screen.getByLabelText('Due date')).toBeRequired();
    expect(latest.frequency).toBe(Frequency.ONE_OFF);

    await user.selectOptions(screen.getByLabelText('Basis'), Basis.PER_PLOT);
    expect(screen.getByLabelText('Frequency')).toBeEnabled();
    expect(latest.frequency).toBe(Frequency.MONTHLY);
    expect(screen.queryByLabelText('Due date')).toBeNull();
  });

  it('warns about a past date, not about today or later', async () => {
    const user = userEvent.setup();
    renderWithApp(<Harness />);
    const date = screen.getByLabelText('Valid from');
    expect(screen.queryByText(WARNING)).toBeNull();

    await user.type(date, addDays(kyivToday(), -1));
    expect(screen.getByText(WARNING)).toBeInTheDocument();

    await user.clear(date);
    await user.type(date, kyivToday());
    expect(screen.queryByText(WARNING)).toBeNull();

    await user.clear(date);
    await user.type(date, addDays(kyivToday(), 1));
    expect(screen.queryByText(WARNING)).toBeNull();
  });
});

describe('validateVersion', () => {
  const t = messages('en');
  const base: VersionValues = { ...emptyVersionValues, validFrom: '2026-11-01', rate: '4,32' };

  it('sends the rate as a normalised string and drops the due date of a repeating basis', () => {
    const result = validateVersion({ ...base, dueDate: '2026-12-01', note: ' ' }, t);
    expect(result.data).toEqual({
      validFrom: '2026-11-01',
      basis: 'PER_PLOT',
      frequency: 'MONTHLY',
      rate: '4.32',
      dueDate: undefined,
      note: undefined,
    });
  });

  it('refuses a bad rate and a missing due date with the server messages', () => {
    expect(validateVersion({ ...base, rate: '4.32001' }, t).errors).toEqual({
      rate: 'The rate must be a non-negative number with at most 4 decimal places',
    });
    expect(
      validateVersion({ ...base, basis: Basis.FIXED_ONE_TIME, frequency: Frequency.ONE_OFF }, t).errors,
    ).toEqual({ dueDate: 'Enter the due date' });
  });
});
