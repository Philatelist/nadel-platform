import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SearchField } from './SearchField';
import { Select } from './Select';
import { TextArea } from './TextArea';
import { TextField } from './TextField';

describe('TextField', () => {
  it('has a label, a 44px height and can be disabled', () => {
    render(<TextField label="Name" disabled />);
    const input = screen.getByLabelText('Name');
    expect(input).toBeDisabled();
    expect(input).toHaveClass('min-h-11', 'border-line-strong');
  });

  it('links error and hint through aria-describedby and marks it invalid', () => {
    render(<TextField label="Name" error="Too short" hint="At least 3" />);
    const input = screen.getByLabelText('Name');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Too short At least 3');
    expect(screen.getByRole('alert')).toHaveTextContent('Too short');
  });
});

describe('SearchField', () => {
  it('is a search input with a label', () => {
    render(<SearchField label="Search" />);
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveAttribute('type', 'search');
  });
});

describe('Select', () => {
  it('has a label, options, error linkage and disabled state', () => {
    render(
      <Select label="Channel" error="Pick one" disabled>
        <option value="a">A</option>
      </Select>,
    );
    const select = screen.getByRole('combobox', { name: 'Channel' });
    expect(select).toBeDisabled();
    expect(select).toHaveAccessibleDescription('Pick one');
  });
});

describe('TextArea', () => {
  it('has a label, error linkage and disabled state', () => {
    render(<TextArea label="Notes" error="Too long" disabled />);
    const area = screen.getByLabelText('Notes');
    expect(area).toBeDisabled();
    expect(area).toHaveAccessibleDescription('Too long');
  });
});
