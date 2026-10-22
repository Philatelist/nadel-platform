import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { plotMeter, withQueries } from '../../test/meterFixtures';
import { ReplaceMeterDialog } from './ReplaceMeterDialog';

const mutate = vi.fn();
let options: { mutation?: { onSuccess?: (m: unknown) => Promise<void>; onError?: (e: unknown) => void } };

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useReplaceMeter: (o: typeof options) => {
    options = o;
    return { mutate, isPending: false };
  },
}));

function open(onReplaced = vi.fn()) {
  renderWithApp(
    withQueries(<ReplaceMeterDialog meter={plotMeter} onReplaced={onReplaced} onClose={vi.fn()} />),
  );
  return onReplaced;
}

describe('ReplaceMeterDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
  });

  it('sends the replacement with exact decimal strings', async () => {
    const user = userEvent.setup();
    open();
    await user.type(screen.getByLabelText(/Final reading of the old meter/), '8205');
    await user.type(screen.getByLabelText('Serial number of the new meter'), 'EM-2');
    await user.type(screen.getByLabelText(/Initial reading of the new meter/), '3.5');
    await user.click(screen.getByRole('button', { name: 'Replace' }));
    expect(mutate).toHaveBeenCalledWith({
      id: 'm1',
      data: expect.objectContaining({
        finalReading: '8205',
        serial: 'EM-2',
        initialReading: '3.5',
      }) as unknown,
    });
  });

  it('requires a serial number and valid readings before sending', async () => {
    const user = userEvent.setup();
    open();
    await user.click(screen.getByRole('button', { name: 'Replace' }));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByText('Enter the serial number.')).toBeInTheDocument();
  });

  it('fills the same serial and initial reading 0 for a wrap-around', async () => {
    const user = userEvent.setup();
    open();
    expect(screen.getByText(/Did the counter wrap around/)).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Final reading of the old meter/), '99999');
    await user.click(screen.getByRole('button', { name: 'Fill in as a wrap-around' }));
    await user.click(screen.getByRole('button', { name: 'Replace' }));
    expect(mutate).toHaveBeenCalledWith({
      id: 'm1',
      data: expect.objectContaining({
        serial: 'EM-104523',
        initialReading: '0',
        finalReading: '99999',
      }) as unknown,
    });
  });

  it('hands the new meter to the caller', async () => {
    const onReplaced = open();
    await options.mutation?.onSuccess?.({ ...plotMeter, id: 'm9' });
    expect(onReplaced).toHaveBeenCalledWith(expect.objectContaining({ id: 'm9' }));
  });

  it('shows catalog text for a refusal with pending readings', async () => {
    open();
    options.mutation?.onError?.(new ApiError(409, { code: 'METER_HAS_PENDING_READINGS' }));
    expect(await screen.findByText(/readings waiting for approval/)).toBeInTheDocument();
  });

  it('shows a field error on the replacement date', async () => {
    open();
    options.mutation?.onError?.(
      new ApiError(409, {
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'replacedOn', code: 'READING_DATE_IN_FUTURE' }],
      }),
    );
    expect(await screen.findByText('The date cannot be in the future')).toBeInTheDocument();
  });
});
