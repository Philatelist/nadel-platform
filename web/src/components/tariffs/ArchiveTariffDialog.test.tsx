import { ApiError, type Tariff } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ArchiveTariffDialog } from './ArchiveTariffDialog';

const mutate = vi.fn();
let options: { mutation: { onError: (e: unknown) => void } };

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useArchiveTariff: (o: typeof options) => {
    options = o;
    return { mutate, isPending: false };
  },
}));

const tariff = {
  id: 't1',
  name: 'Per sotka',
  versions: [
    { id: 'v2', validFrom: '2026-07-01' },
    { id: 'v1', validFrom: '2026-01-01' },
  ],
} as unknown as Tariff;

function renderDialog() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ArchiveTariffDialog tariff={tariff} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('ArchiveTariffDialog', () => {
  beforeEach(() => mutate.mockClear());

  it('allows only dates after the start of the last version and submits endsOn', async () => {
    const user = userEvent.setup();
    renderDialog();
    const field = screen.getByLabelText('Stops applying from');
    expect(field).toHaveAttribute('min', '2026-07-02');
    await user.type(field, '2026-12-01');
    await user.click(screen.getByRole('button', { name: 'Archive' }));
    expect(mutate).toHaveBeenCalledWith({ id: 't1', data: { endsOn: '2026-12-01' } });
  });

  it('does not submit without a date', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.click(screen.getByRole('button', { name: 'Archive' }));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByText('This field is required.')).toBeInTheDocument();
  });

  it('shows the field error of TARIFF_END_BEFORE_LAST_VERSION on the date field', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.type(screen.getByLabelText('Stops applying from'), '2026-12-01');
    await user.click(screen.getByRole('button', { name: 'Archive' }));
    act(() =>
      options.mutation.onError(
        new ApiError(400, {
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'endsOn', code: 'TARIFF_END_BEFORE_LAST_VERSION' }],
        }),
      ),
    );
    expect(
      await screen.findByText('The date must be after the start of the last version.'),
    ).toBeInTheDocument();
  });

  it('shows the message of a 409', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.type(screen.getByLabelText('Stops applying from'), '2026-12-01');
    await user.click(screen.getByRole('button', { name: 'Archive' }));
    act(() => options.mutation.onError(new ApiError(409, { code: 'TARIFF_CHARGED_AFTER_END' })));
    expect(await screen.findByText(/already has charges on or after this date/)).toBeInTheDocument();
  });
});
