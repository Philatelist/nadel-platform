import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { withQueries } from '../../test/meterFixtures';
import { charge } from './fixtures';
import { ReverseChargeDialog } from './ReverseChargeDialog';

const mutate = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useReverseCharge: () => ({ mutate, isPending: false }),
}));

describe('ReverseChargeDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
  });

  it('refuses a blank reason and asks for it', async () => {
    renderWithApp(withQueries(<ReverseChargeDialog charge={charge} onDone={vi.fn()} onClose={vi.fn()} />));
    await userEvent.type(screen.getByLabelText('Reason'), '   ');
    await userEvent.click(screen.getByRole('button', { name: 'Reverse' }));
    expect(screen.getByText('Enter a reason')).toBeInTheDocument();
    expect(mutate).not.toHaveBeenCalled();
  });

  it('sends the trimmed reason', async () => {
    renderWithApp(withQueries(<ReverseChargeDialog charge={charge} onDone={vi.fn()} onClose={vi.fn()} />));
    await userEvent.type(screen.getByLabelText('Reason'), '  Wrong meter  ');
    await userEvent.click(screen.getByRole('button', { name: 'Reverse' }));
    expect(mutate).toHaveBeenCalledWith({ id: 'c1', data: { reason: 'Wrong meter' } });
  });
});
