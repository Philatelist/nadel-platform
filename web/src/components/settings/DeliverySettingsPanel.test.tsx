import {
  ApiError,
  type DeliverySettings,
  type DeliverySettingsUpdate,
  type DeliveryTestEmailResult,
  type DeliveryTestTelegramResult,
  type Permission,
} from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsPage } from '../../pages/SettingsPage';
import { renderWithApp } from '../../test/render';
import { DeliverySettingsPanel } from './DeliverySettingsPanel';

type Callbacks<T> = { onSuccess?: (data: T) => void; onError?: (error: unknown) => void };

let settings: DeliverySettings;
let updateCallbacks: Callbacks<DeliverySettings>;
let emailCallbacks: Callbacks<DeliveryTestEmailResult>;
let telegramCallbacks: Callbacks<DeliveryTestTelegramResult>;
let permissions: Permission[];
const cooperative = {
  memberPlotStatusVisibility: 'OWN_ONLY',
  lossesMode: 'FIXED_PERCENT',
  lossesPercent: '5.00',
  commonConsumerDefaultSplit: 'CONNECTED_PLOTS',
  redAmount: '1000.00',
  redDays: 90,
  autoAllocatePrepayment: true,
};
const update = vi.fn();
const testEmail = vi.fn();
const testTelegram = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetDeliverySettings: () => ({
    data: settings,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useUpdateDeliverySettings: (o: { mutation: Callbacks<DeliverySettings> }) => {
    updateCallbacks = o.mutation;
    return { mutate: update, isPending: false };
  },
  useTestDeliveryEmail: (o: { mutation: Callbacks<DeliveryTestEmailResult> }) => {
    emailCallbacks = o.mutation;
    return { mutate: testEmail, isPending: false };
  },
  useTestDeliveryTelegram: (o: { mutation: Callbacks<DeliveryTestTelegramResult> }) => {
    telegramCallbacks = o.mutation;
    return { mutate: testTelegram, isPending: false };
  },
  useGetSettings: () => ({
    data: cooperative,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useUpdateSettings: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
  useOptionalMe: () => null,
}));

function renderPanel() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <DeliverySettingsPanel />
    </QueryClientProvider>,
  );
}

const configured: DeliverySettings = {
  email: {
    host: 'smtp.example.org',
    port: 587,
    security: 'STARTTLS',
    user: 'mailer',
    fromAddress: 'office@example.org',
    fromName: 'Cooperative',
    passwordSet: true,
    configured: true,
  },
  telegram: { tokenLast4: '1234', botUsername: 'nadiya_bot', configured: true },
  emailRatePerSecond: 2,
  notifyPayments: true,
};

describe('DeliverySettingsPanel', () => {
  beforeEach(() => {
    settings = configured;
    permissions = ['SETTINGS_MANAGE'];
    vi.clearAllMocks();
  });

  it('never prefills the secrets and shows them only as placeholders', () => {
    renderPanel();
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByLabelText('Password')).toHaveAttribute('placeholder', 'saved');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.getByLabelText('Bot token')).toHaveValue('');
    expect(screen.getByLabelText('Bot token')).toHaveAttribute('placeholder', '••••1234');
    expect(screen.getByText('Bot: @nadiya_bot')).toBeInTheDocument();
    expect(screen.getAllByText('configured')).toHaveLength(2);
  });

  it('shows not configured badges for an empty setup', () => {
    settings = {
      email: { passwordSet: false, configured: false },
      telegram: { configured: false },
      emailRatePerSecond: 1,
      notifyPayments: true,
    };
    renderPanel();
    expect(screen.getAllByText('not configured')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Send a test letter' })).toBeDisabled();
  });

  it('leaves an empty password out of the PUT body and sends a typed one', async () => {
    const user = userEvent.setup();
    renderPanel();
    const host = screen.getByLabelText('Mail server (SMTP)');
    await user.clear(host);
    await user.type(host, 'mail.example.org');
    await user.click(screen.getByRole('button', { name: 'Save the mailbox' }));
    const first = update.mock.calls[0]?.[0] as { data: DeliverySettingsUpdate };
    expect(first.data.email).toMatchObject({ host: 'mail.example.org', port: 587, security: 'STARTTLS' });
    expect(first.data.email).not.toHaveProperty('password');
    expect(first.data).not.toHaveProperty('telegram');

    await user.type(screen.getByLabelText('Password'), 's3cret');
    await user.click(screen.getByRole('button', { name: 'Save the mailbox' }));
    const second = update.mock.calls[1]?.[0] as { data: DeliverySettingsUpdate };
    expect(second.data.email?.password).toBe('s3cret');
  });

  it('leaves out the encryption when "no encryption" is chosen', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.selectOptions(screen.getByLabelText('Encryption'), 'no encryption');
    await user.click(screen.getByRole('button', { name: 'Save the mailbox' }));
    const call = update.mock.calls[0]?.[0] as { data: DeliverySettingsUpdate };
    expect(call.data.email).not.toHaveProperty('security');
  });

  it('sends the bot token only when typed', async () => {
    const user = userEvent.setup();
    renderPanel();
    expect(screen.getByRole('button', { name: 'Save the token' })).toBeDisabled();
    await user.type(screen.getByLabelText('Bot token'), '123:ABC');
    await user.click(screen.getByRole('button', { name: 'Save the token' }));
    expect(update).toHaveBeenCalledWith({ data: { telegram: { botToken: '123:ABC' } } });
  });

  it('sends the rate and the switch without the mailbox or the bot', async () => {
    const user = userEvent.setup();
    renderPanel();
    const rate = screen.getByLabelText('Letters per second');
    await user.clear(rate);
    await user.type(rate, '5');
    await user.click(screen.getByRole('switch', { name: 'Notify about payments' }));
    await user.click(screen.getByRole('button', { name: 'Save the options' }));
    expect(update).toHaveBeenCalledWith({ data: { emailRatePerSecond: 5, notifyPayments: false } });
  });

  it('refuses a rate outside 1 to 10', async () => {
    const user = userEvent.setup();
    renderPanel();
    const rate = screen.getByLabelText('Letters per second');
    await user.clear(rate);
    await user.type(rate, '11');
    await user.click(screen.getByRole('button', { name: 'Save the options' }));
    expect(screen.getByText('Enter a whole number from 1 to 10')).toBeInTheDocument();
    expect(update).not.toHaveBeenCalled();
  });

  it('disables the test letter while the form has unsaved edits', async () => {
    const user = userEvent.setup();
    renderPanel();
    const send = screen.getByRole('button', { name: 'Send a test letter' });
    expect(send).toBeEnabled();
    await user.type(screen.getByLabelText('Sender name'), 'x');
    expect(send).toBeDisabled();
  });

  it('shows an accepted test letter with the server reply', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Send a test letter' }));
    expect(testEmail).toHaveBeenCalled();
    emailCallbacks.onSuccess?.({ accepted: true, serverReply: '250 2.0.0 Ok: queued' });
    expect(await screen.findByRole('status')).toHaveTextContent(
      'The mail server accepted the test letter. Server reply: 250 2.0.0 Ok: queued',
    );
  });

  it('shows a refused test letter with the failure text and the reply', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Send a test letter' }));
    emailCallbacks.onSuccess?.({
      accepted: false,
      failureCode: 'SMTP_AUTH_FAILED',
      serverReply: '535 5.7.8 Authentication failed',
    });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Wrong mailbox login or password');
    expect(alert).toHaveTextContent('535 5.7.8 Authentication failed');
  });

  it('shows the problem of a test letter without an own e-mail', async () => {
    renderPanel();
    emailCallbacks.onError?.(new ApiError(409, { code: 'NO_OWN_EMAIL' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('no e-mail address');
  });

  it('shows the problem of a test letter without a mailbox', async () => {
    renderPanel();
    emailCallbacks.onError?.(new ApiError(409, { code: 'DELIVERY_NOT_CONFIGURED' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('not configured yet');
  });

  it('shows the bot name when the check succeeds', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Check the bot' }));
    expect(testTelegram).toHaveBeenCalled();
    telegramCallbacks.onSuccess?.({ ok: true, botUsername: 'nadiya_bot' });
    expect(await screen.findByRole('status')).toHaveTextContent('@nadiya_bot');
  });

  it('shows why the bot check failed', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Check the bot' }));
    telegramCallbacks.onSuccess?.({ ok: false, code: 'TOKEN_INVALID' });
    expect(await screen.findByRole('alert')).toHaveTextContent('The token is not valid');
    telegramCallbacks.onSuccess?.({ ok: false, code: 'TELEGRAM_UNAVAILABLE' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Telegram is unavailable');
  });

  it('confirms a save and reports a failed one', async () => {
    renderPanel();
    updateCallbacks.onError?.(new ApiError(403, { code: 'PERMISSION_DENIED' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/have permission/);
  });

  it('is shown on the settings page only with SETTINGS_MANAGE', () => {
    const page = (
      <QueryClientProvider client={new QueryClient()}>
        <SettingsPage />
      </QueryClientProvider>
    );
    const shown = renderWithApp(page);
    expect(screen.getByLabelText('Bot token')).toBeInTheDocument();
    shown.unmount();
    permissions = ['METERS_VIEW'];
    renderWithApp(page);
    expect(screen.queryByLabelText('Bot token')).toBeNull();
    expect(screen.queryByText('Delivery')).toBeNull();
  });
});
