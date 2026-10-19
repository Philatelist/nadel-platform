import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { RadioGroup } from './RadioGroup';

const options = [
  { value: 'a', label: 'Alpha', description: 'First' },
  { value: 'b', label: 'Beta' },
] as const;

describe('RadioGroup', () => {
  it('is a named group of radios sharing one name, with the value checked', () => {
    render(<RadioGroup legend="Letters" options={options} value="b" onChange={vi.fn()} />);
    const group = screen.getByRole('group', { name: 'Letters' });
    const radios = screen.getAllByRole('radio');
    expect(group).toBeInTheDocument();
    expect(radios).toHaveLength(2);
    expect(radios[0]?.getAttribute('name')).toBe(radios[1]?.getAttribute('name'));
    expect(screen.getByRole('radio', { name: /Beta/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: /Alpha/ })).not.toBeChecked();
    expect(screen.getByText('First')).toBeInTheDocument();
  });

  it('reports the chosen value', async () => {
    const onChange = vi.fn();
    render(<RadioGroup legend="Letters" options={options} value="b" onChange={onChange} />);
    await userEvent.setup().click(screen.getByRole('radio', { name: /Alpha/ }));
    expect(onChange).toHaveBeenCalledWith('a');
  });

  it('shows an error linked to the group', () => {
    render(<RadioGroup legend="Letters" options={options} value="a" onChange={vi.fn()} error="Pick one" />);
    expect(screen.getByRole('alert')).toHaveTextContent('Pick one');
    expect(screen.getByRole('group', { name: 'Letters' })).toHaveAccessibleDescription('Pick one');
  });
});
