import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type PaymentEntryRequest, type PaymentEntryResult } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { PaymentForm } from './PaymentForm';

const enterPayment = vi.fn();
const mutate = vi.fn();

vi.mock('../../me', () => ({
  useCan: () => true,
  useMe: () => ({ permissions: [] }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  enterPayment: (...args: unknown[]) => enterPayment(...args),
  useEnterPayment: () => ({ mutate, isPending: false }),
  useGetPlot: () => ({ data: { id: 'p9', number: '9' } }),
  useListPlotOwnerships: () => ({ data: { plotId: 'p9', current: [], history: [] } }),
  useListMembers: () => ({ data: { items: [] } }),
  useListPlots: () => ({ data: { items: [] } }),
}));

const chargeType = { id: 't1', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false };
const electricityType = { id: 't2', code: 'ELECTRICITY', name: 'Electricity', nameCustomized: false };

function line(chargeId: string, type: typeof chargeType, month: string, open: string, proposed: string) {
  return {
    chargeId,
    kind: 'REGULAR',
    chargeType: type,
    billingMonth: month,
    serviceFrom: `${month}-01`,
    serviceTo: `${month}-28`,
    accruedOn: `${month}-01`,
    open,
    proposed,
  };
}

function result(proposedMembership: string, proposedElectricity: string, digest = 'd1'): PaymentEntryResult {
  return {
    preview: true,
    valid: true,
    problems: [],
    amount: '700.00',
    digest,
    payments: [],
    parts: [
      {
        plotId: 'p9',
        plotNumber: '9',
        amount: '700.00',
        lines: [
          line('c1', chargeType, '2026-01', '600.00', proposedMembership),
          line('c2', electricityType, '2026-02', '250.00', proposedElectricity),
        ],
        resultLines: [],
        allocatedTotal: '700.00',
        automaticTotal: '0.00',
        prepayment: '0.00',
      },
    ],
  } as PaymentEntryResult;
}

function renderForm() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <PaymentForm initialPlotId="p9" />
    </QueryClientProvider>,
  );
}

const wait = { timeout: 3000 };

async function typeAmount(user: ReturnType<typeof userEvent.setup>, text: string) {
  await user.type(screen.getByRole('textbox', { name: 'Сума' }), text);
}

function previewRequests(): PaymentEntryRequest[] {
  return enterPayment.mock.calls.map((call) => call[0] as PaymentEntryRequest);
}

describe('PaymentForm', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    enterPayment.mockReset();
    mutate.mockReset();
    enterPayment.mockResolvedValue(result('600.00', '100.00'));
  });

  it('shows the server proposal and previews again with explicit lines when a line is edited', async () => {
    const user = userEvent.setup();
    renderForm();
    await typeAmount(user, '700');

    const membership = await screen.findByRole(
      'textbox',
      { name: /Сума до зарахування: Членський внесок/ },
      wait,
    );
    expect(membership).toHaveValue('600.00');
    expect(previewRequests().at(-1)).toEqual({
      plotId: 'p9',
      amount: '700.00',
      receivedOn: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) as string,
      source: 'CASH',
    });

    enterPayment.mockResolvedValue(result('400.00', '250.00', 'd2'));
    await user.clear(membership);
    await user.type(membership, '400');

    await waitFor(() => {
      expect(previewRequests().at(-1)?.lines).toEqual([
        { chargeId: 'c1', amount: '400.00' },
        { chargeId: 'c2', amount: '100.00' },
      ]);
    }, wait);
  });

  it('keeps Save disabled while a preview is pending and enables it with the answer', async () => {
    const user = userEvent.setup();
    let answer: (value: PaymentEntryResult) => void = () => undefined;
    enterPayment.mockReturnValue(new Promise<PaymentEntryResult>((resolve) => (answer = resolve)));
    renderForm();
    const save = screen.getByRole('button', { name: 'Зберегти' });
    expect(save).toBeDisabled();

    await typeAmount(user, '700');
    // the debounce has not fired yet, then the request is in flight: still disabled
    expect(save).toBeDisabled();
    await waitFor(() => expect(enterPayment).toHaveBeenCalled(), wait);
    expect(save).toBeDisabled();

    await act(async () => answer(result('600.00', '100.00')));
    await waitFor(() => expect(save).toBeEnabled(), wait);
  });

  it('saves with the digest of the preview and shows the cash receipt number large', async () => {
    const user = userEvent.setup();
    mutate.mockImplementation((_vars: unknown, options: { onSuccess: (r: unknown) => Promise<void> }) =>
      options.onSuccess({
        payments: [
          {
            id: 'pay1',
            groupId: 'g1',
            plotId: 'p9',
            amount: '700.00',
            source: 'CASH',
            receivedOn: '2026-10-28',
            billingMonth: '2026-10',
            receiptNumber: 42,
          },
        ],
      }),
    );
    renderForm();
    await typeAmount(user, '700');
    const save = screen.getByRole('button', { name: 'Зберегти' });
    await waitFor(() => expect(save).toBeEnabled(), wait);
    await user.click(save);

    expect(mutate).toHaveBeenCalledTimes(1);
    const vars = mutate.mock.calls[0]?.[0] as { data: PaymentEntryRequest; params: { preview: boolean } };
    expect(vars.params).toEqual({ preview: false });
    expect(vars.data.digest).toBe('d1');
    expect(vars.data.lines).toBeUndefined();
    expect(await screen.findByText('Квитанція № 42')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ще один платіж' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Ще один платіж' }));
    expect(screen.getByRole('textbox', { name: 'Сума' })).toHaveValue('');
  });

  it('previews again and warns when the save is refused with PREVIEW_OUTDATED', async () => {
    const user = userEvent.setup();
    mutate.mockImplementation((_vars: unknown, options: { onError: (e: unknown) => void }) =>
      options.onError(new ApiError(409, { code: 'PREVIEW_OUTDATED' })),
    );
    renderForm();
    await typeAmount(user, '700');
    const save = screen.getByRole('button', { name: 'Зберегти' });
    await waitFor(() => expect(save).toBeEnabled(), wait);
    const before = enterPayment.mock.calls.length;

    enterPayment.mockResolvedValue(result('600.00', '100.00', 'd-new'));
    await user.click(save);

    expect(
      await screen.findByText('Дані змінилися після перегляду. Перегляньте знову перед збереженням.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(enterPayment.mock.calls.length).toBe(before + 1), wait);
    // the next save carries the new digest
    await waitFor(() => expect(save).toBeEnabled(), wait);
    mutate.mockReset();
    await user.click(save);
    expect((mutate.mock.calls[0]?.[0] as { data: PaymentEntryRequest }).data.digest).toBe('d-new');
  });

  it('warns about a date that is not today', async () => {
    const user = userEvent.setup();
    renderForm();
    expect(screen.queryByText('Дата відрізняється від сьогоднішньої')).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Дата надходження'));
    await user.type(screen.getByLabelText('Дата надходження'), '2020-01-15');
    expect(screen.getByText('Дата відрізняється від сьогоднішньої')).toBeInTheDocument();
  });

  it('keeps the table while an intermediate value exceeds the payment or a charge, then saves once corrected', async () => {
    const user = userEvent.setup();
    renderForm();
    await typeAmount(user, '700');
    const membership = await screen.findByRole(
      'textbox',
      { name: /Сума до зарахування: Членський внесок/ },
      wait,
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Зберегти' })).toBeEnabled(), wait);

    const exceeding = result('900.00', '100.00');
    delete exceeding.digest;
    exceeding.valid = false;
    exceeding.problems = [
      { code: 'ALLOCATION_EXCEEDS_CHARGE', plotId: 'p9', line: 0, chargeId: 'c1' },
      { code: 'ALLOCATION_EXCEEDS_PAYMENT', plotId: 'p9' },
    ];
    exceeding.parts[0]!.allocatedTotal = '1000.00';
    enterPayment.mockResolvedValue(exceeding);
    await user.clear(membership);
    await user.type(membership, '900');

    expect(await screen.findByText('Сума перевищує залишок за нарахуванням', {}, wait)).toBeInTheDocument();
    expect(screen.getByText('Розподілено більше, ніж сума платежу')).toBeInTheDocument();
    expect(screen.getByRole('rowheader', { name: 'Розподілено' }).closest('tr')).toHaveTextContent(
      /1\s000,00 ₴ з 700,00 ₴/,
    );
    expect(screen.getByRole('textbox', { name: /Сума до зарахування: Членський внесок/ })).toHaveValue('900');
    expect(screen.getByRole('button', { name: 'Зберегти' })).toBeDisabled();
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument(), wait);

    enterPayment.mockResolvedValue(result('600.00', '100.00', 'd2'));
    await user.clear(membership);
    await user.type(membership, '600');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Зберегти' })).toBeEnabled(), wait);
    expect(screen.queryByText('Сума перевищує залишок за нарахуванням')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('shows an unparseable line as a problem of that line, not as an endless calculation', async () => {
    const user = userEvent.setup();
    renderForm();
    await typeAmount(user, '700');
    const membership = await screen.findByRole(
      'textbox',
      { name: /Сума до зарахування: Членський внесок/ },
      wait,
    );
    await user.clear(membership);
    await user.type(membership, 'abc');

    expect(await screen.findByText('Вкажіть суму числом, наприклад 250,00', {}, wait)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument(), wait);
    expect(screen.queryByText('Розраховуємо розподіл…')).not.toBeInTheDocument();
    expect(
      screen.getByRole('textbox', { name: /Сума до зарахування: Членський внесок/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Зберегти' })).toBeDisabled();
  });

  it('shows the automatic rows and the note when the server places the remainder', async () => {
    const user = userEvent.setup();
    const automatic = result('600.00', '0.00');
    automatic.parts[0]!.resultLines = [
      { chargeId: 'c1', amount: '600.00', automatic: false },
      { chargeId: 'c2', amount: '100.00', automatic: true },
    ];
    automatic.parts[0]!.automaticTotal = '100.00';
    enterPayment.mockResolvedValue(automatic);
    renderForm();
    await typeAmount(user, '700');
    await screen.findByRole('textbox', { name: /Сума до зарахування: Членський внесок/ }, wait);
    expect(screen.getByText('Залишок буде зараховано автоматично на найстаріші борги')).toBeInTheDocument();
    expect(screen.getByText('автоматично')).toBeInTheDocument();
    expect(screen.getByText('автоматично').closest('tr')).toHaveTextContent(
      /Електро.*250,00 ₴.*100,00 ₴автоматично/,
    );
  });
});
