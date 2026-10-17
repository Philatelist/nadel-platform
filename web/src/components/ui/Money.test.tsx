import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Money } from './Money';

describe('Money', () => {
  it.each([
    ['0.00', '0,00 ₴'],
    ['1250.50', '1 250,50 ₴'],
    ['-350.00', '−350,00 ₴'],
    ['1000000.00', '1 000 000,00 ₴'],
  ])('formats %s', (amount, expected) => {
    render(<Money amount={amount} />);
    expect(screen.getByText(expected, { normalizer: (s) => s })).toHaveClass('font-mono');
  });

  it('is danger for a negative amount with tone auto, not for a positive one', () => {
    render(
      <>
        <Money amount="-350.00" tone="auto" />
        <Money amount="350.00" tone="auto" />
      </>,
    );
    expect(screen.getByText('−350,00 ₴', { normalizer: (s) => s })).toHaveClass('text-danger');
    expect(screen.getByText('350,00 ₴', { normalizer: (s) => s })).not.toHaveClass('text-danger');
  });

  it('is danger with tone danger', () => {
    render(<Money amount="1.00" tone="danger" />);
    expect(screen.getByText('1,00 ₴', { normalizer: (s) => s })).toHaveClass('text-danger');
  });
});
