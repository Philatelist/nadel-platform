import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type PaymentEntryRequest, type PaymentEntryResult } from '@nadel/api-client';
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
vi.mock('../ownership/MemberPicker', () => ({
  MemberPicker: ({
    label,
    value,
    onChange,
  }: {
    label: string;
    value: { id: string; name: string } | null;
    onChange: (m: { id: string; name: string } | null) => void;
  }) =>
    value ? (
      <span>{value.name}</span>
    ) : (
      <button type="button" onClick={() => onChange({ id: 'm1', name: 'Іваненко' })}>
        {`Обрати: ${label}`}
      </button>
    ),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  enterPayment: (...args: unknown[]) => enterPayment(...args),
  useEnterPayment: () => ({ mutate, isPending: false }),
  useGetPlot: () => ({ data: undefined }),
  useListPlotOwnerships: () => ({ data: undefined }),
  useListPlots: () => ({ data: { items: [] } }),
}));

const type = { id: 't1', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false };

function part(plotId: string, plotNumber: string, amount: string, debt: string) {
  return {
    plotId,
    plotNumber,
    amount,
    debt,
    lines: [
      {
        chargeId: `c-${plotId}`,
        kind: 'REGULAR',
        chargeType: type,
        billingMonth: '2026-01',
        serviceFrom: '2026-01-01',
        serviceTo: '2026-01-28',
        accruedOn: '2026-01-01',
        open: debt,
        proposed: amount,
      },
    ],
    resultLines: [],
    allocatedTotal: amount,
    automaticTotal: '0.00',
    prepayment: '0.00',
  };
}

function split(a7: string, a15: string, digest = 'd1'): PaymentEntryResult {
  return {
    preview: true,
    valid: true,
    problems: [],
    amount: '1000.00',
    digest,
    payments: [],
    split: {
      payerMemberId: 'm1',
      plots: [
        { plotId: 'p7', plotNumber: '7', debt: '600.00', proposedAmount: '600.00' },
        { plotId: 'p15', plotNumber: '15', debt: '700.00', proposedAmount: '400.00' },
      ],
    },
    parts: [part('p7', '7', a7, '600.00'), part('p15', '15', a15, '700.00')],
  } as PaymentEntryResult;
}

const wait = { timeout: 3000 };

function requests(): PaymentEntryRequest[] {
  return enterPayment.mock.calls.map((call) => call[0] as PaymentEntryRequest);
}

async function startSplit(user: ReturnType<typeof userEvent.setup>) {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <PaymentForm />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole('radio', { name: 'Кілька ділянок одного платника' }));
  await user.click(screen.getByRole('button', { name: 'Обрати: Платник' }));
  await user.type(screen.getByRole('textbox', { name: 'Сума' }), '1000');
}

describe('PaymentForm split mode', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    enterPayment.mockReset();
    mutate.mockReset();
    enterPayment.mockResolvedValue(split('600.00', '400.00'));
  });

  it('shows the plots of the payer with their debts and the parts the server proposes', async () => {
    const user = userEvent.setup();
    await startSplit(user);

    const seven = await screen.findByRole('textbox', { name: 'Частина платежу для ділянки 7' }, wait);
    expect(seven).toHaveValue('600.00');
    expect(screen.getByRole('textbox', { name: 'Частина платежу для ділянки 15' })).toHaveValue('400.00');
    expect(screen.getByText('700,00 ₴')).toBeInTheDocument();
    const sent = requests().at(-1);
    expect(sent?.split).toEqual({ payerMemberId: 'm1' });
    expect(sent?.plotId).toBeUndefined();
  });

  it('previews again with explicit parts when a part is edited', async () => {
    const user = userEvent.setup();
    await startSplit(user);
    const seven = await screen.findByRole('textbox', { name: 'Частина платежу для ділянки 7' }, wait);

    enterPayment.mockResolvedValue(split('500.00', '500.00', 'd2'));
    await user.clear(seven);
    await user.type(seven, '500');

    await waitFor(() => {
      expect(requests().at(-1)?.split?.parts).toEqual([
        { plotId: 'p7', amount: '500.00' },
        { plotId: 'p15', amount: '400.00' },
      ]);
    }, wait);
  });

  it('keeps the table, shows the mismatch and a line problem inline and keeps Save disabled until corrected', async () => {
    const user = userEvent.setup();
    await startSplit(user);
    const seven = await screen.findByRole('textbox', { name: 'Частина платежу для ділянки 7' }, wait);
    const save = screen.getByRole('button', { name: 'Зберегти' });
    await waitFor(() => expect(save).toBeEnabled(), wait);
    await user.click(screen.getByRole('button', { name: 'Показати розподіл: 7' }));

    const mismatch = split('1.00', '400.00');
    delete mismatch.digest;
    mismatch.valid = false;
    mismatch.problems = [
      { code: 'SPLIT_TOTAL_MISMATCH' },
      { code: 'ALLOCATION_EXCEEDS_CHARGE', plotId: 'p7', line: 0, chargeId: 'c-p7' },
    ];
    enterPayment.mockResolvedValue(mismatch);
    await user.clear(seven);
    await user.type(seven, '1');

    expect(
      await screen.findByText('Сума частин не дорівнює сумі платежу', undefined, wait),
    ).toBeInTheDocument();
    expect(screen.getByText('Сума перевищує залишок за нарахуванням')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Частина платежу для ділянки 7' })).toHaveValue('1');
    expect(save).toBeDisabled();
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument(), wait);

    enterPayment.mockResolvedValue(split('600.00', '400.00', 'd2'));
    await user.clear(seven);
    await user.type(seven, '600');
    await waitFor(() => expect(save).toBeEnabled(), wait);
    expect(screen.queryByText('Сума частин не дорівнює сумі платежу')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('shows an unparseable part as a problem of that part, not as an endless calculation', async () => {
    const user = userEvent.setup();
    await startSplit(user);
    const seven = await screen.findByRole('textbox', { name: 'Частина платежу для ділянки 7' }, wait);
    await user.clear(seven);
    await user.type(seven, 'abc');

    expect(await screen.findByText('Вкажіть суму числом, наприклад 250,00', {}, wait)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument(), wait);
    expect(screen.queryByText('Розраховуємо розподіл…')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Частина платежу для ділянки 7' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Зберегти' })).toBeDisabled();
  });

  it('shows one cash receipt and a link to every part after the save', async () => {
    const user = userEvent.setup();
    mutate.mockImplementation((_vars: unknown, options: { onSuccess: (r: unknown) => Promise<void> }) =>
      options.onSuccess({
        payments: ['p7', 'p15'].map((plotId, i) => ({
          id: `pay${i}`,
          groupId: 'g1',
          plotId,
          amount: i === 0 ? '600.00' : '400.00',
          source: 'CASH',
          receivedOn: '2026-10-28',
          billingMonth: '2026-10',
          receiptNumber: 42,
        })),
      }),
    );
    await startSplit(user);
    const save = screen.getByRole('button', { name: 'Зберегти' });
    await waitFor(() => expect(save).toBeEnabled(), wait);
    await user.click(save);

    const vars = mutate.mock.calls[0]?.[0] as { data: PaymentEntryRequest };
    expect(vars.data.digest).toBe('d1');
    expect(vars.data.split).toEqual({ payerMemberId: 'm1' });
    expect(await screen.findAllByText('Квитанція № 42')).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Ділянка 7' })).toHaveAttribute('href', '/payments/pay0');
    expect(screen.getByRole('link', { name: 'Ділянка 15' })).toHaveAttribute('href', '/payments/pay1');
  });
});
