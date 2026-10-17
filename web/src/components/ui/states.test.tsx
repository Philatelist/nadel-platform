import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';

describe('state components', () => {
  it('EmptyState shows its message', () => {
    renderWithApp(<EmptyState message="Nothing here" />);
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
  });

  it('LoadingState announces its message', () => {
    renderWithApp(<LoadingState message="Loading" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
  });

  it('ErrorState is an alert with a working retry button', async () => {
    const onRetry = vi.fn();
    renderWithApp(<ErrorState message="Boom" onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Boom');
    await userEvent.click(screen.getByRole('button'));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
