import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusDot } from './StatusDot';

describe('StatusDot', () => {
  it('always renders the label and hides the dot from assistive tech', () => {
    const { container } = render(<StatusDot status="RED" label="Debt" />);
    expect(screen.getByText('Debt')).toBeInTheDocument();
    const dot = container.querySelector('[aria-hidden="true"]');
    expect(dot).toHaveClass('bg-status-red');
  });
});
