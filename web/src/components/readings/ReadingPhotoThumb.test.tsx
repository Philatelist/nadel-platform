import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { PendingReading } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { PendingReadingCard } from './PendingReadingCard';

const getReadingPhoto = vi.fn();

vi.mock('../../me', () => ({ useCan: () => true }));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  getReadingPhoto: (...args: unknown[]) => getReadingPhoto(...args),
  useApproveReading: () => ({ mutate: vi.fn(), isPending: false }),
}));

const pending = (hasPhoto: boolean): PendingReading => ({
  reading: {
    id: 'r1',
    meterId: 'm1',
    takenOn: '2026-10-20',
    value: '8250.000',
    status: 'PENDING',
    source: 'MEMBER',
    changeable: false,
    hasPhoto,
  },
  meter: plotMeter,
  warnings: [],
});

function renderCard(hasPhoto: boolean) {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <ul>
        <PendingReadingCard item={pending(hasPhoto)} />
      </ul>
    </QueryClientProvider>,
  );
}

describe('the photo of a pending reading', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    getReadingPhoto.mockReset();
    getReadingPhoto.mockResolvedValue(new Blob(['j'], { type: 'image/jpeg' }));
    URL.createObjectURL = vi.fn(() => 'blob:test/1');
    URL.revokeObjectURL = vi.fn();
  });

  it('shows a thumbnail button that opens the viewer when the reading has a photo', async () => {
    const user = userEvent.setup();
    renderCard(true);
    expect(await screen.findByRole('img', { name: 'Фото лічильника' })).toBeInTheDocument();
    expect(getReadingPhoto).toHaveBeenCalledWith('r1');
    await user.click(screen.getByRole('button', { name: 'Відкрити фото лічильника' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Видалити|Прибрати/ })).not.toBeInTheDocument();
  });

  it('shows nothing and fetches nothing without a photo', () => {
    renderCard(false);
    expect(screen.queryByRole('button', { name: 'Відкрити фото лічильника' })).not.toBeInTheDocument();
    expect(getReadingPhoto).not.toHaveBeenCalled();
  });
});
