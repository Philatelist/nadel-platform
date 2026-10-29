import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { MemberForm } from './MemberForm';

const create = vi.fn();

vi.mock('../me', () => ({ useCan: () => false }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useCreateMember: () => ({ mutate: create, isPending: false }),
  useUpdateMember: () => ({ mutate: vi.fn(), isPending: false }),
}));

describe('MemberForm', () => {
  beforeEach(() => vi.clearAllMocks());

  it('accepts the Telegram channel without a username', async () => {
    renderWithApp(
      <QueryClientProvider client={new QueryClient()}>
        <MemberForm onSaved={vi.fn()} onCancel={vi.fn()} />
      </QueryClientProvider>,
    );
    await userEvent.type(screen.getByLabelText('Last name'), 'Іваненко');
    await userEvent.type(screen.getByLabelText('First name'), 'Іван');
    await userEvent.selectOptions(screen.getByLabelText('Preferred channel'), 'TELEGRAM');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0]![0].data).toMatchObject({ deliveryChannel: 'TELEGRAM' });
    expect(create.mock.calls[0]![0].data.telegramUsername).toBeUndefined();
  });
});
