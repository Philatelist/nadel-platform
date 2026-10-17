import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Badge } from './Badge';

describe('Badge', () => {
  it('renders its text as a pill in the tone colors', () => {
    render(<Badge tone="success">Active</Badge>);
    expect(screen.getByText('Active')).toHaveClass('rounded-full', 'bg-success-soft', 'text-success-ink');
  });

  it('supports the other tones', () => {
    render(
      <>
        <Badge tone="info">I</Badge>
        <Badge tone="warning">W</Badge>
        <Badge tone="neutral">N</Badge>
      </>,
    );
    expect(screen.getByText('I')).toHaveClass('bg-info-soft');
    expect(screen.getByText('W')).toHaveClass('bg-warning-soft');
    expect(screen.getByText('N')).toBeInTheDocument();
  });
});
