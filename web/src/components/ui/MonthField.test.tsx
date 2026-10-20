import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { MonthField } from './MonthField';

describe('MonthField', () => {
  it('is a labelled month input showing the value', () => {
    render(<MonthField label="Month" value="2026-10" onChange={vi.fn()} />);
    const input = screen.getByLabelText('Month');
    expect(input).toHaveAttribute('type', 'month');
    expect(input).toHaveValue('2026-10');
  });

  it('reports the typed month as YYYY-MM', async () => {
    const onChange = vi.fn();
    render(<MonthField label="Month" value="2026-10" onChange={onChange} />);
    const input = screen.getByLabelText('Month');
    await userEvent.clear(input);
    expect(onChange).toHaveBeenCalledWith('');
    onChange.mockClear();
    // fireEvent-style value set is what a browser month picker produces
    const { fireEvent } = await import('@testing-library/react');
    fireEvent.change(input, { target: { value: '2026-11' } });
    expect(onChange).toHaveBeenCalledWith('2026-11');
  });

  it('shows an error linked to the input', () => {
    render(<MonthField label="Month" value="" onChange={vi.fn()} error="Pick a month" />);
    expect(screen.getByLabelText('Month')).toHaveAccessibleDescription('Pick a month');
  });
});
