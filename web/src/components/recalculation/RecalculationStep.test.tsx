import { ApiError } from '@nadel/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { preview } from './recalcFixtures';
import { RecalculationStep } from './RecalculationStep';

const problem = (status: number, code: string) => new ApiError(status, { code, status, title: 'x' });

describe('RecalculationStep', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('books the previewed digest with the trimmed reason and reports the booked recalculation', async () => {
    const booked = preview({ id: 'r1', preview: false, booked: true });
    const run = vi.fn((mode: string) => Promise.resolve(mode === 'preview' ? preview() : booked));
    const onBooked = vi.fn();
    renderWithApp(<RecalculationStep run={run} onBooked={onBooked} onBack={vi.fn()} />);
    await userEvent.type(await screen.findByRole('textbox'), '  Rate fixed  ');
    await userEvent.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    await waitFor(() => expect(onBooked).toHaveBeenCalledWith(booked));
    expect(run).toHaveBeenNthCalledWith(1, 'preview');
    expect(run).toHaveBeenNthCalledWith(2, 'book', 'digest-1', 'Rate fixed');
  });

  it('previews again at once when the preview is outdated and says so', async () => {
    let previews = 0;
    const run = vi.fn((mode: string) => {
      if (mode === 'book') return Promise.reject(problem(409, 'PREVIEW_OUTDATED'));
      previews += 1;
      return Promise.resolve(preview({ digest: `digest-${previews}` }));
    });
    renderWithApp(<RecalculationStep run={run} onBooked={vi.fn()} onBack={vi.fn()} />);
    await userEvent.type(await screen.findByRole('textbox'), 'Why');
    await userEvent.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByText(/The charges changed since the preview/)).toBeInTheDocument();
    await waitFor(() => expect(run.mock.calls.filter(([mode]) => mode === 'preview')).toHaveLength(2));
    expect(await screen.findByRole('button', { name: 'Book the recalculation' })).toBeInTheDocument();
  });

  it('shows another problem as its message and keeps the preview', async () => {
    const run = vi.fn((mode: string) =>
      mode === 'book' ? Promise.reject(problem(409, 'NOTHING_TO_RECALCULATE')) : Promise.resolve(preview()),
    );
    renderWithApp(<RecalculationStep run={run} onBooked={vi.fn()} onBack={vi.fn()} />);
    await userEvent.type(await screen.findByRole('textbox'), 'Why');
    await userEvent.click(screen.getByRole('button', { name: 'Book the recalculation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No changes');
  });

  it('offers Back when the first preview fails', async () => {
    const onBack = vi.fn();
    const run = vi.fn(() => Promise.reject(problem(409, 'RECALCULATION_RANGE_TOO_LONG')));
    renderWithApp(<RecalculationStep run={run} onBooked={vi.fn()} onBack={onBack} />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(onBack).toHaveBeenCalled();
  });
});
