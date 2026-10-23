import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { openMonth } from './billingFixtures';
import { CloseMonthDialog } from './CloseMonthDialog';

const mutate = vi.fn();
let mutation: { onError: (e: unknown) => void; onSuccess: () => Promise<void> };

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useCloseBillingMonth: (o: { mutation: typeof mutation }) => {
    mutation = o.mutation;
    return { mutate, isPending: false };
  },
}));

function open(onDone = vi.fn()) {
  renderWithApp(
    withQueries(
      <CloseMonthDialog month="2026-09" checklist={openMonth.checklist} onDone={onDone} onClose={vi.fn()} />,
    ),
  );
  return onDone;
}

describe('CloseMonthDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
  });

  it('shows the check list again and closes the month on confirm', async () => {
    open();
    expect(screen.getByText('Items waiting in “Not charged”: 3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Close the month' }));
    expect(mutate).toHaveBeenCalledWith({ month: '2026-09' });
  });

  it('calls onDone after a successful close', async () => {
    const onDone = open();
    await mutation.onSuccess();
    expect(onDone).toHaveBeenCalled();
  });

  it('shows the problem text when an earlier month is still open', async () => {
    open();
    mutation.onError(new ApiError(409, { code: 'PERIOD_CLOSE_ORDER' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Close the earlier months first');
  });
});
