import { ApiError } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { ExpenseForm } from './ExpenseForm';

const createExpense = vi.fn();
const heicToJpeg = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  createExpense: (...args: unknown[]) => createExpense(...args),
  useListExpenseCategories: () =>
    queryResult([
      { id: 'c1', kind: 'EXPENSE', systemCode: 'WATER', sortOrder: 1, archived: false },
      { id: 'c2', kind: 'EXPENSE', name: 'Ремонт даху', sortOrder: 2, archived: false },
    ]),
}));
vi.mock('../../lib/heicToJpeg', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/heicToJpeg')>()),
  heicToJpeg: (...args: unknown[]) => heicToJpeg(...args),
}));

function renderForm(onSaved = vi.fn()) {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ExpenseForm onSaved={onSaved} />
    </QueryClientProvider>,
  );
  return onSaved;
}

describe('ExpenseForm', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    createExpense.mockReset();
    heicToJpeg.mockReset();
    let counter = 0;
    URL.createObjectURL = vi.fn(() => `blob:test/${++counter}`);
    URL.revokeObjectURL = vi.fn();
  });

  it('shows the HEIC hint under the attachment buttons', () => {
    renderForm();
    expect(screen.getByText('Фото HEIC з iPhone приймаються в Safari.')).toBeInTheDocument();
  });

  it('shows thumbnails of added files and removes one', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.upload(screen.getByTestId('file-input'), [
      new File(['p'], 'a.png', { type: 'image/png' }),
      new File(['%PDF'], 'b.pdf', { type: 'application/pdf' }),
    ]);
    expect(await screen.findByRole('img', { name: 'a.png' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Відкрити PDF: b.pdf' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'a.png' }));
    expect(await screen.findByRole('dialog', { hidden: true })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'Прибрати: a.png' }));
    expect(screen.queryByRole('img', { name: 'a.png' })).not.toBeInTheDocument();
    expect(screen.getByText('b.pdf')).toBeInTheDocument();
  });

  it('checks the amount before calling the server', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText('Сума'), '0');
    await user.click(screen.getByRole('button', { name: 'Записати витрату' }));
    expect(await screen.findByText('Сума має бути більшою за 0,00.')).toBeInTheDocument();
    expect(createExpense).not.toHaveBeenCalled();
  });

  it('offers the seeded category by catalog name and sends the expense with its files', async () => {
    createExpense.mockResolvedValue({ id: 'e9' });
    const user = userEvent.setup();
    const onSaved = renderForm();
    await user.type(screen.getByLabelText('Сума'), '350,00');
    await user.selectOptions(screen.getByLabelText('Стаття витрат'), 'Вода');
    const pdf = new File(['%PDF'], 'chek.pdf', { type: 'application/pdf' });
    await user.upload(screen.getByTestId('file-input'), pdf);
    await user.click(screen.getByRole('button', { name: 'Записати витрату' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const body = createExpense.mock.calls[0]?.[0] as { expense: Record<string, unknown>; files: File[] };
    expect(body.expense).toMatchObject({ account: 'CASH', amount: '350.00', categoryId: 'c1' });
    expect(body.files).toEqual([pdf]);
  });

  it('converts a HEIC photo to JPEG and keeps it', async () => {
    const jpeg = new File(['j'], 'IMG.jpg', { type: 'image/jpeg' });
    heicToJpeg.mockResolvedValue(jpeg);
    const user = userEvent.setup();
    renderForm();
    await user.upload(screen.getByTestId('file-input'), new File(['h'], 'IMG.heic', { type: 'image/heic' }));
    expect(await screen.findByText('IMG.jpg')).toBeInTheDocument();
  });

  it('says so when a HEIC photo cannot be converted', async () => {
    heicToJpeg.mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderForm();
    await user.upload(screen.getByTestId('file-input'), new File(['h'], 'IMG.heic', { type: 'image/heic' }));
    expect(
      await screen.findByText(
        'Цей браузер не відкриває фото HEIC з iPhone. Додайте фото через Safari або увімкніть на iPhone: Налаштування → Камера → Формати → «Найсумісніший» (фото будуть у JPEG).',
      ),
    ).toBeInTheDocument();
  });

  it('refuses the same file twice and accepts it again after removal', async () => {
    const user = userEvent.setup();
    renderForm();
    const input = screen.getByTestId('file-input');
    await user.upload(input, new File(['p'], 'a.png', { type: 'image/png' }));
    expect(await screen.findByText('a.png')).toBeInTheDocument();
    await user.upload(input, new File(['p'], 'a.png', { type: 'image/png' }));
    expect(await screen.findByText('Цей файл уже додано')).toBeInTheDocument();
    expect(screen.getAllByText('a.png')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Прибрати: a.png' }));
    await user.upload(input, new File(['p'], 'a.png', { type: 'image/png' }));
    expect(await screen.findByText('a.png')).toBeInTheDocument();
    expect(screen.queryByText('Цей файл уже додано')).not.toBeInTheDocument();
  });

  it('refuses a file of a wrong type and has a camera input', async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderForm();
    expect(screen.getByTestId('camera-input')).toHaveAttribute('capture', 'environment');
    await user.upload(screen.getByTestId('file-input'), new File(['x'], 'a.txt', { type: 'text/plain' }));
    expect(await screen.findByText('Дозволені лише фото (JPEG, PNG) і PDF.')).toBeInTheDocument();
  });

  it('shows the cash balance and day of CASH_INSUFFICIENT', async () => {
    createExpense.mockRejectedValue(
      new ApiError(409, { code: 'CASH_INSUFFICIENT', balance: '200.00', date: '2026-10-07' }),
    );
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText('Сума'), '350,00');
    await user.selectOptions(screen.getByLabelText('Стаття витрат'), 'Вода');
    await user.click(screen.getByRole('button', { name: 'Записати витрату' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent?.replace(/\s/g, ' ')).toContain('Недостатньо готівки в касі: залишок 200,00');
    expect(alert.textContent).toContain('07.10.2026');
  });
});
