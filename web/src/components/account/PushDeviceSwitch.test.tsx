import { screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { PushDeviceSwitch } from './PushDeviceSwitch';

let config: { enabled: boolean; publicKey?: string };
let state: string;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetMyPushConfig: () => ({ data: config }),
  listMyPushDevices: async () => [],
}));
vi.mock('../../pwa/pushDevice', () => ({
  reconcile: async () => state,
  turnOn: vi.fn(),
  turnOff: vi.fn(),
}));

function renderSwitch() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <PushDeviceSwitch />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  config = { enabled: true, publicKey: 'AQIDBA' };
  state = 'off';
});

describe('PushDeviceSwitch', () => {
  it('renders nothing when the server has push disabled', () => {
    config = { enabled: false };
    renderSwitch();
    expect(screen.queryByText('Notifications on this device')).toBeNull();
  });

  it('shows an enabled switch when off and a checked one when on', async () => {
    renderSwitch();
    const toggle = await screen.findByRole('switch');
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).not.toBeChecked();
  });

  it('shows the blocked explanation with a disabled switch when denied', async () => {
    state = 'denied';
    renderSwitch();
    expect(await screen.findByText(/blocked in the browser settings/)).toBeInTheDocument();
    expect(screen.getByRole('switch')).toBeDisabled();
  });

  it('shows the iOS text instead of a switch', async () => {
    state = 'ios-needs-install';
    renderSwitch();
    expect(await screen.findByText(/only in the installed app/)).toBeInTheDocument();
    expect(screen.queryByRole('switch')).toBeNull();
  });

  it('shows the unsupported text', async () => {
    state = 'unsupported';
    renderSwitch();
    expect(await screen.findByText(/does not support notifications/)).toBeInTheDocument();
  });
});
