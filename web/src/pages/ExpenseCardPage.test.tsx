import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Route, Routes, useLocation } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../test/meterFixtures';
import { renderWithApp } from '../test/render';
import { ExpenseCardPage } from './ExpenseCardPage';

const useGetExpense = vi.fn();
const reverseExpense = vi.fn();
const downloadExpenseAttachment = vi.fn();
let permissions: string[] = [];

vi.mock('../me', () => ({
  useCan: (p: string) => permissions.includes(p),
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useGetExpense: (...args: unknown[]) => useGetExpense(...args),
  reverseExpense: (...args: unknown[]) => reverseExpense(...args),
  downloadExpenseAttachment: (...args: unknown[]) => downloadExpenseAttachment(...args),
}));
vi.mock('../downloadBlob', () => ({ downloadBlob: vi.fn() }));

const expense = {
  id: 'e1',
  account: 'CASH',
  date: '2026-11-02',
  amount: '350.00',
  bookingMonth: '2026-11',
  category: { id: 'c1', kind: 'EXPENSE', systemCode: 'WATER', sortOrder: 2, archived: false },
  counterparty: 'ТОВ Водоканал',
  attachments: [{ n: 1, fileName: 'chek.pdf', contentType: 'application/pdf', sizeBytes: 2048 }],
  reversed: false,
  createdAt: '2026-11-02T10:00:00Z',
};

function NewPage() {
  const state = useLocation().state as { prefill: { id: string } };
  return <p>prefilled from {state.prefill.id}</p>;
}

function renderCard() {
  return renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <Routes>
        <Route path="/cashbook/expenses/:id" element={<ExpenseCardPage />} />
        <Route path="/cashbook/expenses/new" element={<NewPage />} />
      </Routes>
    </QueryClientProvider>,
    { route: '/cashbook/expenses/e1' },
  );
}

const withFiles = {
  ...expense,
  attachments: [
    { n: 1, fileName: 'chek.pdf', contentType: 'application/pdf', sizeBytes: 2048 },
    { n: 2, fileName: 'one.png', contentType: 'image/png', sizeBytes: 1024 },
    { n: 3, fileName: 'two.jpg', contentType: 'image/jpeg', sizeBytes: 1024 },
  ],
};

describe('ExpenseCardPage', () => {
  beforeEach(() => {
    let counter = 0;
    URL.createObjectURL = vi.fn(() => `blob:test/${++counter}`);
    URL.revokeObjectURL = vi.fn();
    window.localStorage.setItem('nadel.locale', 'uk');
    permissions = ['CASHBOOK_VIEW'];
    useGetExpense.mockReset().mockReturnValue(queryResult(expense));
    reverseExpense.mockReset();
    downloadExpenseAttachment.mockReset();
  });

  it('shows the seeded category by its catalog name and downloads a file', async () => {
    downloadExpenseAttachment.mockResolvedValue(new Blob(['x']));
    const user = userEvent.setup();
    renderCard();
    expect(screen.getByText('Вода')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Завантажити' }));
    await waitFor(() => expect(downloadExpenseAttachment).toHaveBeenCalledWith('e1', 1));
  });

  it('hides the reverse button without CASHBOOK_APPROVE', () => {
    renderCard();
    expect(screen.queryByRole('button', { name: 'Сторнувати і внести заново' })).not.toBeInTheDocument();
  });

  it('hides the reverse button on a reversed expense', () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_APPROVE'];
    useGetExpense.mockReturnValue(queryResult({ ...expense, reversed: true }));
    renderCard();
    expect(screen.queryByRole('button', { name: 'Сторнувати і внести заново' })).not.toBeInTheDocument();
  });

  it('needs a reason, reverses and opens the form prefilled', async () => {
    permissions = ['CASHBOOK_VIEW', 'CASHBOOK_APPROVE'];
    reverseExpense.mockResolvedValue({ ...expense, reversed: true });
    const user = userEvent.setup();
    renderCard();
    await user.click(screen.getByRole('button', { name: 'Сторнувати і внести заново' }));
    await user.click(screen.getByRole('button', { name: 'Сторнувати' }));
    expect(await screen.findByText('Вкажіть причину сторнування.')).toBeInTheDocument();
    expect(reverseExpense).not.toHaveBeenCalled();
    await user.type(screen.getByRole('textbox', { name: /Причина/ }), 'Помилка суми');
    await user.click(screen.getByRole('button', { name: 'Сторнувати' }));
    expect(await screen.findByText('prefilled from e1')).toBeInTheDocument();
    expect(reverseExpense).toHaveBeenCalledWith('e1', { reason: 'Помилка суми' });
  });

  it('shows thumbnails for images and a tile for a PDF, opens the viewer and moves with arrows', async () => {
    useGetExpense.mockReturnValue(queryResult(withFiles));
    downloadExpenseAttachment.mockImplementation((_id: string, n: number) =>
      Promise.resolve(new Blob([`f${n}`])),
    );
    const user = userEvent.setup();
    renderCard();
    expect(await screen.findByRole('img', { name: 'one.png' })).toBeInTheDocument();
    expect(await screen.findByRole('img', { name: 'two.jpg' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Відкрити PDF: chek.pdf' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'one.png' }));
    const dialog = await screen.findByRole('dialog', { hidden: true });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('Перегляд фото one.png');
    expect(screen.getByText('Фото 1 з 2')).toBeInTheDocument();
    await user.keyboard('{ArrowRight}');
    expect(await screen.findByText('Фото 2 з 2')).toBeInTheDocument();
    await user.keyboard('{ArrowLeft}');
    expect(await screen.findByText('Фото 1 з 2')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { hidden: true })).not.toBeInTheDocument());
  });

  it('closes the viewer with its button', async () => {
    useGetExpense.mockReturnValue(queryResult(withFiles));
    downloadExpenseAttachment.mockResolvedValue(new Blob(['x']));
    const user = userEvent.setup();
    renderCard();
    await user.click(await screen.findByRole('button', { name: 'two.jpg' }));
    await user.click(await screen.findByRole('button', { name: 'Закрити' }));
    expect(screen.queryByRole('dialog', { hidden: true })).not.toBeInTheDocument();
  });

  it('opens a PDF in a new tab from a blob URL', async () => {
    useGetExpense.mockReturnValue(queryResult(withFiles));
    downloadExpenseAttachment.mockResolvedValue(new Blob(['%PDF']));
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    const user = userEvent.setup();
    renderCard();
    await user.click(screen.getByRole('button', { name: 'Відкрити PDF: chek.pdf' }));
    await waitFor(() =>
      expect(open).toHaveBeenCalledWith(expect.stringMatching(/^blob:/), '_blank', 'noopener'),
    );
    const blob = (URL.createObjectURL as ReturnType<typeof vi.fn>).mock.calls.at(-1)?.[0] as Blob;
    expect(blob.type).toBe('application/pdf');
    open.mockRestore();
  });
});
