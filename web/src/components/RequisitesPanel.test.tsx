import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type CooperativeSettings } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { RequisitesPanel } from './RequisitesPanel';

interface Options {
  onSuccess: (data: CooperativeSettings) => void;
  onError: (error: unknown) => void;
}

let mutate: ReturnType<typeof vi.fn>;
let options: Options;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useUpdateSettings: (o: { mutation: Options }) => {
    options = o.mutation;
    return { mutate, isPending: false };
  },
}));

const settings: CooperativeSettings = {
  memberPlotStatusVisibility: 'OWN_ONLY',
  lossesMode: 'FIXED_PERCENT',
  lossesPercent: '5.00',
  commonConsumerDefaultSplit: 'CONNECTED_PLOTS',
  redAmount: '1000.00',
  redDays: 90,
  autoAllocatePrepayment: true,
  officialName: 'Sonyachne',
  edrpou: '12345678',
};

function renderPanel() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <RequisitesPanel saved={settings} />
    </QueryClientProvider>,
  );
}

describe('RequisitesPanel', () => {
  beforeEach(() => {
    mutate = vi.fn();
  });

  it('shows the four fields and a read-only EDRPOU code', () => {
    renderPanel();
    expect(screen.getByLabelText('Official name')).toHaveValue('Sonyachne');
    expect(screen.getByLabelText('Legal address')).toHaveValue('');
    expect(screen.getByLabelText('Bank name')).toHaveValue('');
    expect(screen.getByLabelText('IBAN')).toHaveValue('');
    const edrpou = screen.getByLabelText('EDRPOU code');
    expect(edrpou).toHaveValue('12345678');
    expect(edrpou).toHaveAttribute('readonly');
    expect(screen.getByText('Changed by the platform operator')).toBeInTheDocument();
  });

  it('trims and upper-cases the IBAN and sends empty strings for cleared fields', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.type(screen.getByLabelText('IBAN'), '  ua21 3223 1300 0002 6007 2335 6600 1 ');
    await user.click(screen.getByRole('button', { name: 'Save the requisites' }));
    expect(mutate).toHaveBeenCalledWith({
      data: {
        officialName: 'Sonyachne',
        legalAddress: '',
        bankName: '',
        iban: 'UA21 3223 1300 0002 6007 2335 6600 1',
      },
    });
  });

  it('shows the IBAN field error and does not report a save', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.type(screen.getByLabelText('IBAN'), 'bad');
    await user.click(screen.getByRole('button', { name: 'Save the requisites' }));
    options.onError(
      new ApiError(400, { code: 'IBAN_INVALID', errors: [{ field: 'iban', code: 'IBAN_INVALID' }] }),
    );
    expect(await screen.findByText('The IBAN is not valid')).toBeInTheDocument();
    expect(screen.queryByText('Settings saved.')).toBeNull();
  });
});
