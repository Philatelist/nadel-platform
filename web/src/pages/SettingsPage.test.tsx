import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type CooperativeSettings, type Permission } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { SettingsPage } from './SettingsPage';

interface Options {
  onSuccess: (data: CooperativeSettings) => void;
  onError: (error: unknown) => void;
}

let settingsQuery: { data?: CooperativeSettings; error: unknown; isPending: boolean; isError: boolean };
let permissions: Permission[];
let mutate: ReturnType<typeof vi.fn>;
let isPending: boolean;
let calls: Options[];
const refetch = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetSettings: () => ({ ...settingsQuery, refetch }),
  useGetDeliverySettings: () => ({ data: undefined, error: null, isPending: true, isError: false, refetch }),
  useUpdateSettings: (o: { mutation: Options }) => {
    calls.push(o.mutation);
    return { mutate, isPending };
  },
}));
vi.mock('../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));

function renderPage() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <SettingsPage />
    </QueryClientProvider>,
  );
}

const settings: CooperativeSettings = {
  memberPlotStatusVisibility: 'OWN_ONLY',
  lossesMode: 'FIXED_PERCENT',
  lossesPercent: '5.00',
  commonConsumerDefaultSplit: 'CONNECTED_PLOTS',
  redAmount: '1000.00',
  redDays: 90,
  autoAllocatePrepayment: true,
};

describe('SettingsPage', () => {
  beforeEach(() => {
    permissions = ['SETTINGS_MANAGE'];
    calls = [];
    settingsQuery = {
      data: settings,
      error: null,
      isPending: false,
      isError: false,
    };
    mutate = vi.fn();
    isPending = false;
  });

  it('shows only the sections the permissions allow', () => {
    permissions = ['METERS_VIEW', 'METERS_MANAGE'];
    renderPage();
    expect(screen.getByRole('link', { name: 'Meter tree' })).toHaveAttribute('href', '/settings/meter-tree');
    expect(screen.queryByRole('radio')).toBeNull();
  });

  it('shows the common consumers section only with SETTINGS_MANAGE', () => {
    renderPage();
    expect(
      screen.getByText('How the consumption of each common meter', { exact: false }),
    ).toBeInTheDocument();
    permissions = ['METERS_VIEW'];
    renderPage();
    expect(screen.getAllByText('Common consumers')).toHaveLength(1);
  });

  it('hides the meter tree link without METERS_MANAGE', () => {
    renderPage();
    expect(screen.queryByRole('link', { name: 'Meter tree' })).toBeNull();
    expect(screen.getAllByRole('radio').length).toBeGreaterThan(0);
  });

  it('shows both sections with both permissions', () => {
    permissions = ['SETTINGS_MANAGE', 'METERS_VIEW', 'METERS_MANAGE'];
    renderPage();
    expect(screen.getByRole('link', { name: 'Meter tree' })).toBeInTheDocument();
    expect(screen.getAllByRole('radio').length).toBeGreaterThan(0);
  });

  it('shows the requisites block with SETTINGS_MANAGE: four fields and a read-only EDRPOU code', () => {
    settingsQuery = { ...settingsQuery, data: { ...settings, edrpou: '12345678' } };
    renderPage();
    expect(screen.getByLabelText('Official name')).toBeInTheDocument();
    expect(screen.getByLabelText('Legal address')).toBeInTheDocument();
    expect(screen.getByLabelText('Bank name')).toBeInTheDocument();
    expect(screen.getByLabelText('IBAN')).toBeInTheDocument();
    expect(screen.getByLabelText('EDRPOU code')).toHaveValue('12345678');
    expect(screen.getByLabelText('EDRPOU code')).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: 'Save the requisites' })).toBeInTheDocument();
  });

  it('offers the requisites to nobody without SETTINGS_MANAGE', () => {
    settingsQuery = { ...settingsQuery, data: { ...settings, edrpou: '12345678' } };
    permissions = ['METERS_VIEW', 'METERS_MANAGE', 'PLOTS_VIEW'];
    renderPage();
    expect(screen.queryByLabelText('IBAN')).toBeNull();
    expect(screen.queryByLabelText('Official name')).toBeNull();
    expect(screen.queryByLabelText('EDRPOU code')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Save the requisites' })).toBeNull();
  });

  it('shows a loading state', () => {
    settingsQuery = { error: null, isPending: true, isError: false };
    renderPage();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByRole('radio')).toBeNull();
  });

  it('offers Try again when loading fails', async () => {
    settingsQuery = { error: new Error('x'), isPending: false, isError: true };
    renderPage();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('shows the current value and the explanation', () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Cooperative settings' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Own plots only/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: /All plots/ })).not.toBeChecked();
    expect(screen.getByText(/only by decision of the general meeting/)).toBeInTheDocument();
  });

  it('keeps Save disabled until the choice changes', async () => {
    const user = userEvent.setup();
    renderPage();
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: /All plots/ }));
    expect(save).toBeEnabled();
    await user.click(screen.getByRole('radio', { name: /Own plots only/ }));
    expect(save).toBeDisabled();
  });

  it('disables Save while the request is pending', () => {
    isPending = true;
    renderPage();
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
  });

  it('sends the choice and confirms the save', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('radio', { name: /All plots/ }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(mutate).toHaveBeenCalledWith({ data: { memberPlotStatusVisibility: 'ALL_PLOTS' } });
    calls[0]?.onSuccess({ ...settings, memberPlotStatusVisibility: 'ALL_PLOTS' });
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
  });

  it('shows a problem notice when saving fails', async () => {
    renderPage();
    calls[0]?.onError(new ApiError(403, { code: 'PERMISSION_DENIED' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/have permission/);
  });

  it('saves the red threshold as a partial update with the amount as text', async () => {
    const user = userEvent.setup();
    renderPage();
    const save = screen.getByRole('button', { name: 'Save the threshold' });
    expect(save).toBeDisabled();
    const amount = screen.getByLabelText(/Red from the debt amount/);
    await user.clear(amount);
    await user.type(amount, '1500,5');
    const days = screen.getByLabelText(/Red from the debt age/);
    await user.clear(days);
    await user.type(days, '60');
    await user.click(save);
    expect(mutate).toHaveBeenCalledWith({ data: { redAmount: '1500.50', redDays: 60 } });
    calls[1]?.onSuccess({ ...settings, redAmount: '1500.50', redDays: 60 });
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
  });

  it('refuses a malformed threshold before sending', async () => {
    const user = userEvent.setup();
    renderPage();
    const amount = screen.getByLabelText(/Red from the debt amount/);
    await user.clear(amount);
    await user.type(amount, 'abc');
    await user.click(screen.getByRole('button', { name: 'Save the threshold' }));
    expect(screen.getByText('Enter an amount such as 1500.00')).toBeInTheDocument();
    expect(mutate).not.toHaveBeenCalled();
  });

  it('hides the threshold from those who cannot manage settings', () => {
    permissions = ['METERS_VIEW', 'METERS_MANAGE'];
    renderPage();
    expect(screen.queryByLabelText(/Red from the debt amount/)).toBeNull();
  });

  describe('losses', () => {
    const percent = () => screen.getByLabelText(/Losses percentage/);

    it('saves the mode and the percentage as a partial update', async () => {
      const user = userEvent.setup();
      renderPage();
      const save = screen.getByRole('button', { name: 'Save the losses' });
      expect(save).toBeDisabled();
      expect(percent()).toHaveValue('5.00');
      await user.clear(percent());
      await user.type(percent(), '7,5');
      await user.click(save);
      expect(mutate).toHaveBeenCalledWith({ data: { lossesMode: 'FIXED_PERCENT', lossesPercent: '7.5' } });
      calls[2]?.onSuccess({ ...settings, lossesPercent: '7.50' });
      expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
    });

    it('keeps Save disabled when the same percentage is retyped in another form', async () => {
      const user = userEvent.setup();
      renderPage();
      const save = screen.getByRole('button', { name: 'Save the losses' });
      await user.clear(percent());
      await user.type(percent(), '5');
      expect(save).toBeDisabled();
      await user.clear(percent());
      await user.type(percent(), '5,0');
      expect(save).toBeDisabled();
      await user.clear(percent());
      await user.type(percent(), '5.01');
      expect(save).toBeEnabled();
    });

    it('describes the losses for both modes', () => {
      renderPage();
      expect(screen.getByText(/added to the consumption charge of each plot/)).toBeInTheDocument();
      expect(screen.getByText(/losses run splits the losses of the node/)).toBeInTheDocument();
    });

    it('explains the proportional mode and sends it', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.click(screen.getByRole('radio', { name: /Proportional/ }));
      expect(screen.getByText(/charged by the losses run/)).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Save the losses' }));
      expect(mutate).toHaveBeenCalledWith({ data: { lossesMode: 'PROPORTIONAL', lossesPercent: '5.00' } });
    });

    it('refuses a malformed percentage before sending', async () => {
      const user = userEvent.setup();
      renderPage();
      await user.clear(percent());
      await user.type(percent(), 'abc');
      await user.click(screen.getByRole('button', { name: 'Save the losses' }));
      expect(screen.getByText(/Enter a percentage from 0 to 100/)).toBeInTheDocument();
      expect(mutate).not.toHaveBeenCalled();
    });

    it('shows the server problem when the percentage is out of range', async () => {
      renderPage();
      calls[2]?.onError(new ApiError(400, { code: 'INVALID' }));
      expect(await screen.findByRole('alert')).toBeInTheDocument();
    });

    it('is hidden from those who cannot manage settings', () => {
      permissions = ['METERS_VIEW'];
      renderPage();
      expect(screen.queryByLabelText(/Losses percentage/)).toBeNull();
    });
  });

  it('shows the prepayment switch with the server value and saves the flag', async () => {
    renderPage();
    const sw = screen.getByRole('switch', { name: 'Allocate prepayment automatically' });
    expect(sw).toBeChecked();
    expect(screen.getByText(/placed on new charges as soon as they are booked/)).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(sw);
    await user.click(screen.getByRole('button', { name: 'Save the payment settings' }));
    expect(mutate).toHaveBeenCalledWith({ data: { autoAllocatePrepayment: false } });
  });

  it('offers no prepayment switch without SETTINGS_MANAGE', () => {
    permissions = ['METERS_VIEW'];
    renderPage();
    expect(screen.queryByRole('switch')).toBeNull();
  });
});
