import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { RegisterMeterDialog } from './RegisterMeterDialog';

const mutate = vi.fn();
let options: { mutation: { onError: (e: unknown) => void } };

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useRegisterMeter: (o: typeof options) => {
    options = o;
    return { mutate, isPending: false };
  },
  useListPlots: () => ({ data: { items: [] }, error: null, isPending: false, isError: false }),
}));

function open() {
  return renderWithApp(
    withQueries(
      <RegisterMeterDialog
        initialPlot={{ id: 'p7', number: '7' }}
        onRegistered={vi.fn()}
        onClose={vi.fn()}
      />,
    ),
  );
}

describe('RegisterMeterDialog', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
  });

  it('has no name field for a plot meter and sends the plot', async () => {
    const user = userEvent.setup();
    open();
    expect(screen.queryByLabelText('Name')).toBeNull();
    await user.type(screen.getByLabelText('Serial number'), ' EM-1 ');
    await user.type(screen.getByLabelText('Initial reading'), '8,205.5');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(mutate).toHaveBeenCalledTimes(1);
    const data = (mutate.mock.calls[0]?.[0] as { data: Record<string, unknown> }).data;
    expect(data).toMatchObject({
      kind: 'PLOT',
      resource: 'ELECTRICITY',
      plotId: 'p7',
      serial: 'EM-1',
      initialReading: '8205.5',
    });
    expect(data).not.toHaveProperty('name');
  });

  it('asks for a name instead of a plot and the resource for other kinds', async () => {
    const user = userEvent.setup();
    open();
    await user.selectOptions(screen.getByLabelText('Kind of meter'), 'COMMON_CONSUMER');
    expect(screen.queryByLabelText('Resource')).toBeNull();
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Serial number'), 'W-1');
    await user.type(screen.getByLabelText('Initial reading'), '0');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByText('This field is required.')).toBeInTheDocument();
    expect(mutate).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText('Name'), 'Well 1');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const data = (mutate.mock.calls[0]?.[0] as { data: Record<string, unknown> }).data;
    expect(data).toMatchObject({ kind: 'COMMON_CONSUMER', name: 'Well 1' });
    expect(data).not.toHaveProperty('plotId');
  });

  it('refuses a reading with more than three decimals without calling the server', async () => {
    const user = userEvent.setup();
    open();
    await user.type(screen.getByLabelText('Serial number'), 'EM-1');
    await user.type(screen.getByLabelText('Initial reading'), '1.2345');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(
      screen.getByText('A reading must be a non-negative number with at most 3 decimal places'),
    ).toBeInTheDocument();
    expect(mutate).not.toHaveBeenCalled();
  });

  it('shows server problems as texts from the catalog', () => {
    open();
    options.mutation.onError(new ApiError(409, { code: 'METER_ACTIVE_EXISTS' }));
    return screen.findByText('The plot already has an active meter of this resource');
  });

  it('shows a field error on the installation date', async () => {
    open();
    options.mutation.onError(
      new ApiError(409, {
        code: 'READING_DATE_IN_FUTURE',
        errors: [{ field: 'installedOn', code: 'READING_DATE_IN_FUTURE' }],
      }),
    );
    expect(await screen.findByText('The date cannot be in the future')).toBeInTheDocument();
  });
});
