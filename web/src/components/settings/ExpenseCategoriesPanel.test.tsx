import { ApiError } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider, useMutation } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryResult } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { ExpenseCategoriesPanel } from './ExpenseCategoriesPanel';

const updateExpenseCategory = vi.fn();
const reorderExpenseCategories = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  // the generated hooks call their module's own functions, so the hooks are replaced
  useUpdateExpenseCategory: ({ mutation }: { mutation: object }) =>
    useMutation({
      mutationFn: (v: { id: string; data: unknown }) => updateExpenseCategory(v.id, v.data),
      ...mutation,
    }),
  useCreateExpenseCategory: ({ mutation }: { mutation: object }) =>
    useMutation({ mutationFn: () => Promise.resolve(), ...mutation }),
  useReorderExpenseCategories: ({ mutation }: { mutation: object }) =>
    useMutation({
      mutationFn: (v: { data: unknown }) => reorderExpenseCategories(v.data),
      ...mutation,
    }),
  useListExpenseCategories: () =>
    queryResult([
      { id: 'c1', kind: 'EXPENSE', systemCode: 'WATER', sortOrder: 1, archived: false },
      { id: 'c2', kind: 'EXPENSE', name: 'Ремонт даху', sortOrder: 2, archived: false },
      { id: 'i1', kind: 'INCOME', systemCode: 'GRANTS', sortOrder: 1, archived: true },
    ]),
}));

function renderPanel() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ExpenseCategoriesPanel />
    </QueryClientProvider>,
  );
}

describe('ExpenseCategoriesPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    updateExpenseCategory.mockReset();
    reorderExpenseCategories.mockReset().mockResolvedValue(undefined);
  });

  it('shows seeded names from the catalog and renamed ones as typed', () => {
    renderPanel();
    expect(screen.getByText('Вода')).toBeInTheDocument();
    expect(screen.getByText('Ремонт даху')).toBeInTheDocument();
    expect(screen.getByText('Гранти та пожертви')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Повернути' })).toBeInTheDocument();
  });

  it('sends the whole list in the new order', async () => {
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getAllByRole('button', { name: 'Нижче' })[0]!);
    await waitFor(() =>
      expect(reorderExpenseCategories).toHaveBeenCalledWith({ kind: 'EXPENSE', ids: ['c2', 'c1'] }),
    );
  });

  it('says the name is taken', async () => {
    updateExpenseCategory.mockRejectedValue(new ApiError(409, { code: 'CATEGORY_NAME_TAKEN' }));
    const user = userEvent.setup();
    renderPanel();
    await user.click(screen.getAllByRole('button', { name: 'Перейменувати' })[0]!);
    const input = screen.getByLabelText('Назва');
    await user.clear(input);
    await user.type(input, 'Матеріали');
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    expect(await screen.findByText('Така стаття вже є.')).toBeInTheDocument();
  });
});
