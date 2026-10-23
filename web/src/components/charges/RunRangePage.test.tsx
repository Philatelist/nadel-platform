import type { Permission } from '@nadel/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { withQueries } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { RunRangePage, type RangeRunResult } from './RunRangePage';

interface Result extends RangeRunResult {
  lines: string[];
}

const mutate = vi.fn();

vi.mock('../../me', () => ({
  useCan: (p: Permission) => p === 'CHARGES_RUN',
  useMe: () => ({ permissions: ['CHARGES_RUN'] }),
  useOptionalMe: () => null,
}));
vi.mock('../../kyivToday', () => ({ kyivToday: () => '2026-10-23' }));

function renderPage(hasLines?: (r: Result) => boolean) {
  renderWithApp(
    withQueries(
      <RunRangePage<Result>
        title="Run"
        description="d"
        runner={{ mutate, isPending: false }}
        renderPreview={(r) => <p>{r.lines.length} lines</p>}
        previewTitle={() => 'Preview'}
        hasLines={hasLines}
        blockedTexts={{ note: 'note', action: 'action', nothing: 'Nothing to book here' }}
        history={null}
      />,
    ),
  );
}

async function preview(result: Result) {
  await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
  const callbacks = mutate.mock.calls.at(-1)?.[1] as { onSuccess: (r: Result) => void };
  await act(async () => callbacks.onSuccess(result));
}

const base: Result = { digest: 'd', created: 0, plots: 0, total: '0.00', billingMonth: '2026-10', lines: [] };

describe('RunRangePage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'en');
    mutate.mockReset();
  });

  it('does not offer to book a preview without any line', async () => {
    renderPage((r) => r.lines.length > 0);
    await preview(base);
    expect(screen.getByText('Nothing to book here')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Book' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'action' })).toBeNull();
  });

  it('offers to book a preview that has lines', async () => {
    renderPage((r) => r.lines.length > 0);
    await preview({ ...base, lines: ['a'] });
    expect(screen.getByRole('button', { name: 'Book' })).toBeInTheDocument();
    expect(screen.queryByText('Nothing to book here')).toBeNull();
  });
});
