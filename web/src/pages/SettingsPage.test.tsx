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
});
