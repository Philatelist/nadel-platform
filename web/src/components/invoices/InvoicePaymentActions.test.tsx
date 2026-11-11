import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithApp } from '../../test/render';
import { InvoicePaymentActions } from './InvoicePaymentActions';

const payment = {
  link: 'https://qr.bank.gov.ua/abc',
  iban: 'UA213223130000026007233566001',
  purpose: 'Contribution, plot 9',
};

describe('InvoicePaymentActions', () => {
  it('renders nothing without payment', () => {
    const { container } = renderWithApp(<InvoicePaymentActions />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows a safe bank link and both copy buttons', () => {
    renderWithApp(<InvoicePaymentActions payment={payment} />);
    const link = screen.getByRole('link', { name: 'Pay in the bank app' });
    expect(link).toHaveAttribute('href', payment.link);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByRole('button', { name: 'Copy IBAN' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy payment purpose' })).toBeInTheDocument();
  });

  it('hides the bank link when it is not an NBU link', () => {
    renderWithApp(<InvoicePaymentActions payment={{ ...payment, link: 'https://evil.example/x' }} />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy IBAN' })).toBeInTheDocument();
  });
});
